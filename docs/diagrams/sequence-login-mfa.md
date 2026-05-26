# Diagrama de Secuencia — Login + MFA (HU-F02 / HU-F03)

**Fuente:** `specs/HU-F02-F03-login-mfa/SPEC.md` §5.1.
**Última actualización:** 2026-05-25.

Representa el flujo de autenticación de dos pasos: (1) login con email+password que dispara el envío de un OTP por email, y (2) verificación del OTP que emite el access token JWT y la cookie de refresh. Implementa **TAC-S1 — Autenticar actores** (`ARCHITECTURE.md` §6).

---

## Fase 1 — Login (credenciales → OTP enviado)

```mermaid
sequenceDiagram
  autonumber
  actor U as Usuario
  participant FE as Frontend (React)
  participant LC as LoginController
  participant LS as LoginService
  participant PG as PostgreSQL (app.users)
  participant R as Redis
  participant BCE as BCryptPasswordEncoder
  participant LAT as LoginAttemptTracker
  participant N as NotificationService
  participant MH as MailHog (SMTP)
  participant A as AuditLogger
  participant ES as ElasticSearch

  U->>FE: Ingresa email + password
  FE->>FE: Valida formato (RFC 5322, no vacío)
  FE->>+LC: POST /api/v1/auth/login<br/>{email, password}
  LC->>LC: Bean Validation (@Valid)
  LC->>+LS: login(request, clientIp)

  LS->>+PG: SELECT * FROM app.users<br/>WHERE LOWER(email)=? AND estado='ACTIVE'
  PG-->>-LS: User row (o vacío)
  alt usuario no existe o no activo
    LS-->>LC: throw INVALID_CREDENTIALS<br/>(mensaje genérico — anti-enumeration)
    LC-->>FE: 401 / 403
  end

  LS->>+R: GET lockout:{userId}
  R-->>-LS: vacío o "1"
  alt lockout activo
    LS-->>LC: throw ACCOUNT_LOCKED
    LC-->>FE: 423 Locked
  end

  LS->>+BCE: matches(rawPassword, hashed)
  BCE-->>-LS: true/false
  alt password incorrecto
    LS->>LAT: increment(userId)
    LAT->>R: INCR login:attempts:{userId}<br/>+ TTL 15min
    alt 3er intento fallido
      LAT->>R: SET lockout:{userId} 1 (TTL 15min)
      LAT->>A: emit(ACCOUNT_LOCKED)
    end
    LS-->>LC: throw INVALID_CREDENTIALS
    LC-->>FE: 401
  end

  LS->>LS: tempSessionId = UUIDv4()<br/>otp = 6 dígitos aleatorios

  rect rgb(240, 248, 255)
    note right of R: TTL 5 min en todas las claves
    LS->>R: SET temp-session:{tsId} {userId, email, role}
    LS->>R: SET otp:{tsId} "123456"
    LS->>R: SET mfa:attempts:{tsId} 0
    LS->>R: SET mfa:resends:{tsId} 0
  end
  LS->>R: DEL login:attempts:{userId} (reset)

  LS->>+N: sendOtpEmail(email, otp) (async)
  N->>+MH: SMTP MAIL FROM → "Tu código: 123456"
  MH-->>-N: ok
  N-->>-LS: dispatched

  LS->>+A: emit(LOGIN_ATTEMPT, result=ALLOWED, ip)
  A->>+ES: POST /audit/_doc
  ES-->>-A: 201
  A-->>-LS: ok

  LS-->>-LC: LoginResponse(tempSessionId, expiresIn=300)
  LC-->>-FE: 200 OK
  FE->>FE: Guarda tempSessionId EN MEMORIA<br/>(NO localStorage)
  FE->>U: Navega a /mfa-verify (timer 5min)
```

---

## Fase 2 — Verificación de OTP → emisión de JWT

```mermaid
sequenceDiagram
  autonumber
  actor U as Usuario
  participant FE as Frontend
  participant MC as MfaController
  participant MS as MfaService
  participant R as Redis
  participant MV as MFAValidator
  participant JWT as JwtIssuer (jjwt)
  participant A as AuditLogger
  participant ES as ElasticSearch

  U->>U: Recibe email en MailHog UI :8025<br/>copia código 123456
  U->>FE: Ingresa OTP en 6 inputs, "Verificar"
  FE->>+MC: POST /api/v1/auth/mfa/verify<br/>{tempSessionId, code}
  MC->>+MS: verify(request, clientIp)

  MS->>+R: GET temp-session:{tsId}
  R-->>-MS: {userId, email, role} o nil
  alt sesión expirada / inexistente
    MS-->>MC: throw SESSION_EXPIRED
    MC-->>FE: 401
  end

  MS->>+R: GET otp:{tsId}
  R-->>-MS: "123456"
  MS->>+MV: validate(provided, stored)
  MV-->>-MS: match?
  alt OTP incorrecto
    MS->>R: INCR mfa:attempts:{tsId}
    alt attempts ≥ 3
      MS->>R: DEL temp-session, otp, mfa:attempts, mfa:resends
      MS->>A: emit(MFA_FAILED_SESSION_KILLED)
      MS-->>MC: throw TOO_MANY_ATTEMPTS
      MC-->>FE: 403
    end
    MS-->>MC: throw INVALID_OTP
    MC-->>FE: 400
  end

  rect rgb(240, 255, 240)
    note over MS,JWT: OTP válido → emitir tokens
    MS->>+JWT: issueAccessToken(userId, role)
    JWT-->>-MS: JWT HS256 (sub, role, jti) — exp 15min
    MS->>MS: refreshToken = secureRandom 64 chars
    MS->>R: SET refresh-token:{refreshId}<br/>{userId, createdAt, rotationCount=0}<br/>TTL 7 días
  end

  rect rgb(255, 245, 238)
    note over MS,R: Invalida la sesión temporal (OTP de un solo uso)
    MS->>R: DEL temp-session:{tsId}
    MS->>R: DEL otp:{tsId}
    MS->>R: DEL mfa:attempts:{tsId}
    MS->>R: DEL mfa:resends:{tsId}
  end

  MS->>+A: emit(MFA_VERIFIED, userId, ip)
  A->>+ES: POST /audit/_doc
  ES-->>-A: 201
  A-->>-MS: ok

  MS-->>-MC: MfaVerifyResponse(accessToken, expiresIn=900, user)
  MC->>MC: Set-Cookie: refreshToken=...<br/>HttpOnly, Secure, SameSite=Strict,<br/>Path=/api/v1/auth/refresh, Max-Age=604800
  MC-->>-FE: 200 OK { accessToken, user }
  FE->>FE: Access token en AuthContext (memoria)<br/>Refresh token en cookie HttpOnly
  FE->>U: Redirige a /dashboard
```

---

## Decisiones registradas (extracto de SPEC §5.1 / §6)

- **Anti-enumeration.** El error "email no existe" y "password incorrecto" responden ambos con `401 INVALID_CREDENTIALS` mensaje genérico. **Nunca** se filtra cuál de los dos falló.
- **`tempSessionId` en memoria, no en localStorage.** Es un identificador opaco con TTL 5min; si el usuario refresca la página pierde la sesión temporal (comportamiento aceptado — equivalente a volver a login).
- **OTP de un solo uso.** Tras verificación exitosa, las 4 claves de Redis (`temp-session`, `otp`, `mfa:attempts`, `mfa:resends`) se borran. No se puede re-usar el mismo OTP ni mismo `tempSessionId`.
- **Lockout por intentos.** 3 intentos fallidos → cuenta bloqueada 15min en Redis (`lockout:{userId}`). El contador `login:attempts:{userId}` también vive en Redis con TTL 15min. Implementa **ESC-S1** (`ARCHITECTURE.md` §13).
- **Refresh token rotativo.** Cada llamada a `/auth/refresh` rota el token (DELETE viejo, SET nuevo con `rotationCount+1`). El refresh token vive solo en cookie HttpOnly (nunca en JS).

## Tácticas materializadas

| Táctica | Componente | Dónde se ve aquí |
|---|---|---|
| TAC-S1 — Autenticar actores | `MFAValidator`, `MfaService` | Fase 2, validación del OTP |
| TAC-S3 — Revocar acceso | `LoginAttemptTracker` | Fase 1, lockout tras 3 intentos |
| TAC-S4 — Mantener registro | `AuditLogger` | Eventos `LOGIN_ATTEMPT`, `MFA_VERIFIED`, `ACCOUNT_LOCKED`, `MFA_FAILED_SESSION_KILLED` |
| TAC-M1 — Intermediario | `NotificationService` → `JavaMailSender` → MailHog | Fase 1, envío del OTP por SMTP |

## Flujos no representados aquí

- **Refresh de access token** (SPEC §5.2.1): `POST /auth/refresh` con cookie → nuevo JWT + rotación.
- **Logout** (SPEC §5.2.2): `POST /auth/logout` → blacklist del `jti` en Redis + DELETE refresh token + Set-Cookie expirada.
- **Reenvío de OTP** (SPEC §5.2.3): `POST /auth/mfa/resend` con cooldown 30s y máximo 3 reenvíos por sesión.
- **Errores 4xx/5xx** (SPEC §5.3): credenciales inválidas, cuenta bloqueada, OTP expirado, demasiados intentos en MFA.
