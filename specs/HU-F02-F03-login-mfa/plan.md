# plan.md — HU-F02 + HU-F03 Login + MFA (bundle)

> Plan técnico derivado de `specs/HU-F02-F03-login-mfa/SPEC.md` v1.0.
> Estado: **aprobado** (SDD Paso 2). Q1–Q4 cerradas + recorte de alcance D18 acordado 2026-05-20.

> **Recorte de alcance 2026-05-20 (D18):** se difieren `/refresh` y `/logout` a una mini-HU post-MVP. Este bundle implementa **solo `/login` + `/mfa/verify` + `/mfa/resend`** + AuthContext + ProtectedRoute + JWT validation filter. Access token de 15 min; al expirar, el frontend redirige a `/login` (sin refresh transparente). El "logout" del frontend es soft: limpia AuthContext y navega a `/login` (sin llamar backend). Ver D18, §7 (Lote E diferido) y §11 (cronograma actualizado).

---

## 1. Objetivo

Implementar el flujo de autenticación de 2 pasos: `POST /api/v1/auth/login` (credenciales → OTP por email + sesión temporal en Redis) y `POST /api/v1/auth/mfa/verify` (OTP → par JWT access+refresh). Cubre además `/mfa/resend`, `/refresh` (rotación), `/logout` (revoke + blacklist). Backend stateless (todo el estado de auth en Redis). Frontend con `AuthContext` + `ProtectedRoute` + interceptor JWT — la base que toda HU posterior reusa.

Es el bundle **más grande** del MVP. La SPEC asigna Día 2; realista, **derrama parte a Día 3** (ver §11 abajo).

---

## 2. Decisiones técnicas concretas (cerradas salvo Q en §10)

| # | Decisión | Justificación |
|---|---|---|
| D1 | **JWT con jjwt 0.12.x** (ya en pom). Algoritmo HS256 con secret `JWT_SECRET` (env, mínimo 256 bits). Access token claims: `{sub: userId, role, jti: UUID}`, exp 15 min. | STACK.md §8.1; jjwt ya aprobado e instalado en HU-F01. |
| D2 | **Refresh token** = 64 chars URL-safe base64 (SecureRandom). El token plain es la cookie HttpOnly; la **key Redis** es `refresh-token:{sha256(token)}` (no el token raw — evita exponer secretos en el keyspace de Redis si se snapshotea). El value es JSON con `{userId, createdAt, rotationCount}`. | Spec §5.1 paso 26 dice `refresh-token:{tokenId}` sin especificar. Hash protege contra fuga via `KEYS`/dumps. Cookie viaja vía SameSite=Strict + HttpOnly + Secure. |
| D3 | **Redis client**: `StringRedisTemplate` para counters/flags + un `RedisTemplate<String,String>` con valores serializados manualmente vía Jackson (`ObjectMapper`) para los objetos compuestos (`TempSessionData`, `RefreshTokenData`). Evita la complejidad de `@RedisHash` y mantiene control sobre TTLs. | Minimiza acoplamiento; los TTLs son explícitos en cada `opsForValue().set(key, value, Duration)`. |
| D4 | **OTP**: 6 dígitos generados con `SecureRandom.nextInt(1_000_000)` y formato `%06d`. Comparación **timing-safe** con `MessageDigest.isEqual(bytes, bytes)` para evitar canal lateral. | Defensa en profundidad; cost mínimo. |
| D5 | **`JwtAuthenticationFilter` (skeleton de Día 0)** se implementa: extrae `Authorization: Bearer <token>`, valida firma+exp con `JwtService`, consulta `revoked:{jti}` en Redis, popula `SecurityContext` con `UsernamePasswordAuthenticationToken` (principal = `AuthenticatedUser(userId, role)`, authorities = `ROLE_<rol>`). Sin token o token inválido → continúa el chain sin auth (endpoints públicos pasan; endpoints protegidos caen en 401). | Día 0 dejó la firma + `TODO HU-F02`; este bundle lo cierra. |
| D6 | **`JwtService` (skeleton de Día 0)** se implementa: `generateAccessToken(userId, role) → String`, `validate(token) → Claims`, `extractJti(token) → String`. Lanza excepciones específicas: `TokenExpiredException`, `TokenInvalidException`. | Mismo: Día 0 dejó la API; ahora se rellena. |
| D7 | **Notifier refactor**: la interfaz `Notifier` (HU-F01) se extiende con `sendOtpEmail(OtpEmailCommand)` y `sendAccountLockedEmail(AccountLockedCommand)`. El impl `WelcomeEmailDispatcher` se renombra a `MailNotifier` y absorbe los tres métodos (mismo patrón JavaMailSender + Thymeleaf + audit `WELCOME_EMAIL_FAILED`/análogos). Refactor en este PR. | Mantener un solo bean con una sola responsabilidad ("enviar emails de auth"). El método `sendWelcomeEmail` queda intacto en comportamiento; los tests de HU-F01 deberían seguir pasando con un rename del bean en el test. |
| D8 | **Email templates nuevas**: `otp.html` (OTP destacado tipográficamente, texto "Si no fuiste tú, ignora este email") y `account-locked.html` (mensaje del bloqueo, duración, instrucción de contacto). Mismo estilo inline-CSS del `welcome.html`. | Spec §9.2. Reutilizan el SpringTemplateEngine ya cableado. |
| D9 | **Códigos de error nuevos** (D10 de HU-F01 sigue vigente: code SCREAMING_SNAKE en `message`, GlobalExceptionHandler los mapea). Se agregan a `validation-messages.properties` (backend) y `messages.es.ts` (frontend): `VALIDATION_INVALID_OTP`, `INVALID_CREDENTIALS`, `ACCOUNT_LOCKED`, `ACCOUNT_NOT_ACTIVE`, `MFA_INVALID_CODE`, `MFA_CODE_EXPIRED`, `MFA_SESSION_INVALIDATED`, `TEMP_SESSION_INVALID`, `RESEND_COOLDOWN_ACTIVE`, `MAX_RESENDS_EXCEEDED`, `REFRESH_TOKEN_INVALID`, `TOKEN_REVOKED`, `TOKEN_EXPIRED`. La regla D14 (single-field promotion) se mantiene. | Mismo patrón establecido en HU-F01; cero ambigüedad. |
| D10 | **`UserRepository`** se extiende con `Optional<User> findByEmailIgnoreCase(String)`. Spring Data lo deriva automático. El query usa el índice funcional `idx_users_email_lower` de la migración V2. | Cero costo, mejor performance que `findAll().filter`. |
| D11 | **`LoginAttemptTracker`** y **`TempSessionManager`** son componentes encapsulados (TAC-M3) — wrappers tipados alrededor del `StringRedisTemplate`. Cada uno maneja sus keys y TTLs; los services no conocen el shape de las keys. | Aislamiento ante cambios futuros del shape (ej: si se mueve a sessión persistente). |
| D12 | **AuthContext frontend**: React Context con `{user, accessToken, isAuthenticated, login(...), logout(), refresh()}`. Estado **en memoria** (no localStorage, spec §12.3). El access token vive en el provider; el refresh viaja como cookie HttpOnly invisible al JS. | Spec mandato. |
| D13 | **JWT interceptor + single-flight refresh**: el axios response interceptor detecta 401 `TOKEN_EXPIRED`, dispara una **única** `/refresh` (queueing concurrente: si ya hay una pending, las requests subsecuentes esperan a esa misma Promise), aplica el nuevo access token al `AuthContext` y reintenta cada request original. Si `/refresh` devuelve 401 → limpia context, redirige `/login`. | Sin single-flight, N requests paralelas disparan N refreshes y el último gana mientras los demás obtienen 401 (rotación). Patrón estándar. |
| D14 | **`ProtectedRoute`** wraps rutas; `useAuth().isAuthenticated === false` → `<Navigate to="/login" />`. **`useSession` (inerte en HU-F01) se reemplaza** por `useAuth` que sí lee el AuthContext. Touch a `RegisterPage.tsx` (cambia el import). | Cumple la promesa que dejamos en HU-F01 Q2. |
| D15 | **CI ya tiene `service: postgres`** (commit de HU-F01); se agrega **`service: redis: redis:7-alpine ports:6379:6379`** al job backend y `JWT_SECRET` (generado al vuelo en el workflow vía `openssl rand -base64 64`) como env. | Paralelo a postgres; mismo patrón. |
| D16 | **Coverage objetivo realista ~60-70%** (continúa D17 de HU-F01). Foco de tests: `LoginService`, `MfaService`, `RefreshService`, `JwtService`, `LoginAttemptTracker`, `MailNotifier` (las nuevas variantes), `jwtInterceptor` frontend (lógica clave para todas las HUs siguientes). Skip tests de Countdown/OTPInput salvo lógica no trivial. | Memoria viva [[feedback-coverage-vs-velocidad]]: no perseguir 80% mientras el plazo aprieta. |
| D17 | **CSRF**: queda **disabled** en SecurityConfig (ya está así). Protección CSRF viene de `SameSite=Strict` + `HttpOnly` + `Secure` en la refresh cookie + `Authorization: Bearer` (no auto-enviado por el browser). Documentado aquí. | Spec asume diseño stateless con Bearer; CSRF tokens serían redundantes. |
| **D18** | **Recorte de alcance 2026-05-20**: este bundle implementa **solo `/login` + `/mfa/verify` + `/mfa/resend`** + AuthContext + ProtectedRoute + JWT validation filter. **`/refresh` y `/logout` se difieren a una mini-HU post-MVP** (`HU-F0X-token-rotation-logout`). Consecuencias: (a) `TokenIssuer` solo emite access token (no refresh, no cookie); (b) `RefreshTokenStore` y `TokenBlacklist` NO se crean — quedan como deuda; (c) `jwtInterceptor` (D13) simplificado: en 401 redirige directo a `/login`, sin intento de refresh; (d) "logout" del frontend = clear AuthContext + navigate (sin backend call); (e) tests/DoD de refresh+logout fuera de scope. | Decisión humana 2026-05-20 al recibir alerta de cronograma (§11). Sprint 1 cierra Día 5 y faltan HU-F04+F20 + HU-F06; aplicar el recorte ROADMAP §3.4 estilo "promover/cortar". El access token de 15 min es suficiente para una demo MVP — la UX subóptima (re-login cada 15 min) se documenta en notas del PR. |

---

## 3. Cambios de dependencias

**Backend: NINGUNO**. Todo lo necesario ya está aprobado/instalado en HU-F01:
- `jjwt 0.12.6` (api + impl + jackson) — JWT
- `spring-boot-starter-data-redis` — cliente Redis
- `spring-boot-starter-mail` + `thymeleaf` — emails OTP + bloqueo
- `spring-boot-starter-security` — filtros JWT
- `spring-boot-starter-validation` — Bean Validation de los nuevos DTOs

**Frontend: NINGUNO**. Reusa lo de HU-F01 (axios, react-query, rhf+zod, @hookform/resolvers, react-router).

**STACK.md / CONVENTIONS.md: sin cambios**.

---

## 4. Reuso de Día 1 y cosas nuevas

**Reutilizado tal cual** (mismo archivo):
- `shared/web/{ErrorResponse, FieldErrorItem, TraceIdFilter, GlobalExceptionHandler, ValidationMessages}` — todos los endpoints nuevos heredan el patrón de error y el `X-Trace-Id`
- `audit/{Auditor, AuditLogger, AuditEvent, AuditEventType}` — agregar entries al enum para los nuevos eventos (10 más)
- `notification/Notifier` — extender interface (D7)
- `auth/domain/User` + `auth/repository/UserRepository` — agregar `findByEmailIgnoreCase` (D10)
- `auth/exception/*` — agregar nuevas excepciones
- `config/SecurityConfig` — agregar `/auth/login`, `/auth/mfa/verify`, `/auth/mfa/resend`, `/auth/refresh` a `permitAll`; `/auth/logout` queda `authenticated`
- `frontend/src/lib/{apiClient, errorParser, messages.es}` — extender con nuevos códigos + el interceptor JWT (D13)
- `frontend/src/features/auth/hooks/useSession` — **se borra**; reemplaza `useAuth` (D14)
- `frontend/src/pages/LoginPage` (stub HU-F01) — **se reemplaza** por la versión real

**Nuevo** (estructura):

```
backend/src/main/java/co/edu/unbosque/bloomtrade/
├── auth/
│   ├── controller/{LoginController, MfaController, TokenController}.java
│   ├── service/{LoginService, MfaService, RefreshService, LogoutService}.java
│   ├── security/
│   │   ├── JwtService.java                    (IMPL — Día 0 era stub)
│   │   ├── JwtAuthenticationFilter.java       (IMPL — Día 0 era stub)
│   │   ├── AuthenticatedUser.java             (record principal)
│   │   ├── TokenIssuer.java                   (componente: emite par access+refresh)
│   │   ├── TokenBlacklist.java                (componente: revoked:{jti} en Redis)
│   │   └── RefreshTokenStore.java             (componente: refresh-token:{hash} en Redis)
│   ├── ratelimit/
│   │   ├── LoginAttemptTracker.java           (login:attempts + lockout en Redis)
│   │   └── MfaAttemptTracker.java             (mfa:attempts + mfa:resends + cooldown)
│   ├── session/
│   │   ├── TempSessionManager.java            (temp-session + otp en Redis)
│   │   ├── TempSessionData.java               (record JSON-serializado)
│   │   └── OtpGenerator.java                  (SecureRandom 6 dígitos + isEqual timing-safe)
│   ├── dto/
│   │   ├── LoginRequest.java, LoginResponse.java
│   │   ├── MfaVerifyRequest.java, MfaVerifyResponse.java
│   │   ├── MfaResendRequest.java, MfaResendResponse.java
│   │   ├── TokenRefreshResponse.java
│   │   └── UserSummary.java                   (shared con HU-F04 futuro)
│   └── exception/{InvalidCredentialsException, AccountLockedException,
│                  AccountNotActiveException, TempSessionInvalidException,
│                  MfaInvalidCodeException, MfaCodeExpiredException,
│                  MfaSessionInvalidatedException, ResendCooldownActiveException,
│                  MaxResendsExceededException, RefreshTokenInvalidException,
│                  TokenRevokedException, TokenExpiredException}.java
├── notification/
│   ├── MailNotifier.java                      (RENAME WelcomeEmailDispatcher → expandido)
│   └── dto/{OtpEmailCommand, AccountLockedEmailCommand}.java
└── config/
    ├── RedisConfig.java                       (ObjectMapper + RedisTemplate<String,String>)
    └── SecurityConfig.java                    (MODIFICADO: permitAll + filtro real)

backend/src/main/resources/templates/email/
├── otp.html                                   (NEW)
└── account-locked.html                        (NEW)

frontend/src/
├── features/auth/
│   ├── context/AuthContext.tsx                (AuthProvider + useAuth)
│   ├── hooks/{useLogin, useMFAVerify, useMFAResend, useLogout}.ts
│   ├── schemas/{login.ts, mfa.ts}             (zod)
│   └── components/{LoginForm, MFAVerifyForm, ResendButton}.tsx
├── components/{OTPInput, Countdown, ProtectedRoute, AppHeader}.tsx
├── pages/{LoginPage (REPLACE), MFAVerifyPage, DashboardPage (placeholder)}.tsx
├── lib/apiClient.ts                           (MODIFICADO: + interceptor JWT D13)
└── App.tsx                                    (MODIFICADO: agrega rutas + ProtectedRoute)
```

---

## 5. Hallazgos / deuda de Día 0 + Día 1 a abordar dentro de este bundle

| # | Hallazgo | Acción en este bundle |
|---|---|---|
| G1 | `JwtService` y `JwtAuthenticationFilter` son skeletons que lanzan `UnsupportedOperationException` (Día 0). | Se implementan completos (D5, D6). |
| G2 | `JWT_SECRET` y `JWT_REFRESH_SECRET` están en `.env.example` pero sin uso. Solo se usa `JWT_SECRET` (HS256). El `JWT_REFRESH_SECRET` queda registrado como deuda — no se usa porque el refresh es opaco (no JWT). | Quitar `JWT_REFRESH_SECRET` de `.env.example` o documentarlo como reservado. |
| G3 | `useSession` (frontend) era stub inerte de HU-F01 (Q2 resuelto). | Reemplazo por `useAuth` real (D14); `RegisterPage` lo consume sin guard inerte. |
| G4 | `LoginPage` stub de HU-F01 (Q2 también). | Reemplazo completo por implementación real. |
| G5 | El bean `WelcomeEmailDispatcher` se referencia por nombre en tests de HU-F01 (`WelcomeEmailDispatcherTest`). Al renombrar a `MailNotifier` (D7) hay que actualizar el test. | Touch al test de HU-F01 (rename + verificación de los nuevos métodos por separado). |

---

## 6. Mapeo arquitectónico (spec §8) → paquetes

| Componente spec | Paquete Java | Notas |
|---|---|---|
| `LoginController` / `MfaController` / `TokenController` | `auth/controller/` | TokenController agrupa `/refresh` y `/logout` (cohesivos: rotación + revocación) |
| `LoginService` / `MfaService` / `RefreshService` / `LogoutService` | `auth/service/` | `@Transactional(readOnly=true)` donde aplica; Redis no es transaccional |
| `MFAValidator` (spec §8.1) | `auth/session/OtpGenerator` (genera) + `auth/service/MfaService` (valida) | Decisión: la "validación" es comparación timing-safe contra Redis, no requiere clase dedicada |
| `LoginAttemptTracker` (spec §8.1) | `auth/ratelimit/LoginAttemptTracker` | INCR + GET + TTL en Redis encapsulado |
| `JwtIssuer` (spec §8.1) | `auth/security/TokenIssuer` | Emite el par; usa `JwtService` para el access, `SecureRandom`+SHA256 para el refresh |
| `TempSessionManager` (spec §8.1) | `auth/session/TempSessionManager` | SET/GET/DELETE de las 4 keys atadas a `tempSessionId` |
| `OtpGenerator` (spec §8.1) | `auth/session/OtpGenerator` | Reutilizable |
| `OtpEmailDispatcher` (spec §8.1) | `notification/MailNotifier` (método `sendOtpEmail`) | Consolidado, D7 |
| `IAuthentication` (spec §8.3) | Interface Java: `auth/security/AuthenticationPort` (sin prefijo `I`, D1 HU-F01) implementada por `JwtService` o un service dedicado | Útil cuando otros módulos necesiten validar tokens en pruebas |

---

## 7. Orden de implementación — 9 lotes con HITOs

```
LOTE A — Fundaciones backend (JWT + Redis + filter)
  └── RedisConfig, JwtService impl, AuthenticatedUser, TokenBlacklist,
      RefreshTokenStore, JwtAuthenticationFilter impl (validate + populate context)
                                                      ← HITO 1 (compila)

LOTE B — Notification refactor + templates
  └── Notifier interface +2 métodos · MailNotifier (rename WelcomeEmailDispatcher
      + sendOtpEmail + sendAccountLockedEmail) · otp.html · account-locked.html
  └── Actualiza WelcomeEmailDispatcherTest → MailNotifierTest (HU-F01 sigue verde)

LOTE C — Login flow (paso 1)
  └── LoginAttemptTracker · TempSessionManager · TempSessionData · OtpGenerator
  └── LoginRequest/Response DTOs · 4 excepciones (InvalidCredentials, AccountLocked,
      AccountNotActive, etc.) · GlobalExceptionHandler handlers
  └── LoginService (todo el flow spec §5.1 paso 1-16) · LoginController
  └── SecurityConfig: + permitAll login              ← HITO 2 (curl login → 200 + tempSessionId)

LOTE D — MFA flow (paso 2 + resend)
  └── MfaAttemptTracker · MfaVerifyRequest/Response · MfaResendRequest/Response
  └── 4 excepciones (MfaInvalidCode, MfaCodeExpired, MfaSessionInvalidated, ResendCooldown,
      MaxResends) · GlobalExceptionHandler handlers
  └── MfaService (verify + resend) · MfaController · TokenIssuer (genera par)
  └── SecurityConfig: + permitAll mfa/verify, mfa/resend
                                                      ← HITO 3 (curl verify → 200 + JWT + cookie)

~~LOTE E — Refresh + Logout + filtro autenticado~~  **DIFERIDO POR D18 (post-MVP)**
  ~~└── RefreshService · LogoutService · TokenController (/refresh, /logout)~~
  ~~└── 3 excepciones (RefreshTokenInvalid, TokenRevoked, TokenExpired) + handlers~~
  ~~└── JwtAuthenticationFilter activado: requests autenticadas pasan; revocadas → 401~~
  └── ⚠️ JwtAuthenticationFilter sí se activa (Lote A), pero **sin check de blacklist**
      (no hay `TokenBlacklist` porque no hay /logout). Filtro: valida firma + exp +
      popula SecurityContext. Tokens expirados → 401 TOKEN_EXPIRED.

LOTE F — Tests backend + CI Redis
  └── Unit (Mockito): LoginServiceTest, MfaServiceTest, RefreshServiceTest,
      TokenIssuerTest, JwtServiceTest, OtpGeneratorTest, LoginAttemptTrackerTest,
      MfaAttemptTrackerTest, MailNotifierTest (3 métodos)
  └── IT: AuthFlowIT (Redis real + Postgres real: login → MFA → token → /refresh
      → /logout → token revocado → 401)
  └── CI: service redis al job backend + JWT_SECRET generado en el workflow
                                                      ← HITO 5 (mvn verify verde)

LOTE G — Frontend infra (AuthContext + interceptor + ProtectedRoute)
  └── AuthContext + AuthProvider · useAuth · types/api.ts extendido
  └── apiClient.ts MODIFICADO: jwtInterceptor con single-flight refresh (D13)
  └── ProtectedRoute · borra useSession · RegisterPage actualizada

LOTE H — Frontend pages (login + MFA + dashboard placeholder)
  └── loginSchema · mfaSchema · useLogin/useMFAVerify/useMFAResend/useLogout
  └── OTPInput · Countdown · ResendButton · LoginForm · LoginPage (REPLACE stub)
  └── MFAVerifyPage · DashboardPage (placeholder con "hola {nombreCompleto}" y logout)
  └── AppHeader (user + logout) · App.tsx con rutas + ProtectedRoute
                                                      ← HITO 6 (E2E manual: registrarse → login → MFA → dashboard)

LOTE I — Tests frontend + cierre
  └── AuthContext.test (provider state + login/logout flows)
  └── LoginForm.test · MFAVerifyForm.test · ProtectedRoute.test
  └── jwtInterceptor.test (single-flight refresh behavior)
  └── APRENDIZAJES.md Día 2-3 · tasks.md actualizado · PR
                                                      ← HITO 7 (npm verify verde + PR abierto)
```

---

## 8. Estrategia de tests (CONVENTIONS §7, [[feedback-coverage-vs-velocidad]])

**Unit (Mockito puro):**
- `JwtServiceTest` — emisión + validación + expiración + tampering
- `OtpGeneratorTest` — formato 6 dígitos, distribución (statistical sanity), comparación timing-safe
- `LoginAttemptTrackerTest` — INCR, GET, lockout TTL (Redis mockeado)
- `MfaAttemptTrackerTest` — análogo
- `LoginServiceTest` — happy + INVALID_CREDENTIALS + ACCOUNT_LOCKED + ACCOUNT_NOT_ACTIVE + tercer intento setea lockout
- `MfaServiceTest` — happy + INVALID_CODE + CODE_EXPIRED + tercer fail invalida sesión + resend cooldown + max resends
- `RefreshServiceTest` — happy con rotación + REFRESH_TOKEN_INVALID
- `LogoutServiceTest` — happy + blacklist correctamente seteada con TTL = exp restante
- `MailNotifierTest` — los 3 métodos (welcome, otp, account-locked) — el de welcome verifica que HU-F01 no se rompió

**Integración (Postgres + Redis reales del compose, perfil `test`):**
- `AuthFlowIT` — E2E de la cadena completa: registro (reusa flujo HU-F01) → POST /login → leer email OTP de @MockBean JavaMailSender → POST /mfa/verify → recibir JWT + cookie → request a un endpoint protegido (`/actuator/health` con auth o un endpoint stub) → POST /refresh con cookie → reintento OK → POST /logout → request con el access token revocado devuelve 401. Verifica audit events: `LOGIN_ATTEMPT`, `MFA_VERIFIED`, `TOKEN_REFRESHED`, `LOGOUT`, `ACCESS_DENIED`.
- `LockoutFlowIT` — 3 logins fallidos → cuenta bloqueada → 4° login 423 → email de bloqueo en MailHog mock → tras TTL simulado (manipular Redis directo) → login OK.
- `OpenApiContractIT` (existente) — extender para verificar los 5 endpoints nuevos documentados.

**CI Redis service** (D15): agregar al workflow `services: redis: image: redis:7-alpine, ports: 6379:6379, options: healthcheck pg_isready equivalente con `redis-cli ping``. `JWT_SECRET` generado al vuelo en el workflow.

**Frontend (Vitest + RTL):**
- `AuthContext.test.tsx` — provider state, login() llena state, logout() limpia
- `LoginForm.test.tsx` — submit deshabilitado al inicio, válido habilita + dispara mutación, 401 muestra banner
- `MFAVerifyForm.test.tsx` — 6 dígitos auto-advance, paste de 6 dígitos auto-llena, submit cuando completo, 400 INVALID_CODE muestra mensaje
- `ProtectedRoute.test.tsx` — no auth → redirect a /login; auth → renderiza children
- `jwtInterceptor.test.ts` — 401 dispara `/refresh`, request original se reintenta, **single-flight**: 5 requests paralelas dispararon UN solo `/refresh`

---

## 9. Trazabilidad criterios de aceptación → artefacto

| Escenario Gherkin spec §11 | Verificado por |
|---|---|
| Login + MFA exitoso (happy) | `AuthFlowIT#shouldLoginAndVerifyMfa...` + manual HITO 3 |
| Credenciales inválidas / email no registrado / mensaje genérico | `LoginServiceTest` + `AuthFlowIT` |
| Tercer intento activa bloqueo | `LockoutFlowIT` + `LoginAttemptTrackerTest` |
| Login con cuenta ya bloqueada (423) | `LockoutFlowIT` |
| Bloqueo se libera tras 15 min | `LoginAttemptTrackerTest` (verifica TTL) |
| Login exitoso resetea contador | `LoginServiceTest#shouldClearAttemptsOnSuccess` |
| Cuenta BLOCKED → 403 ACCOUNT_NOT_ACTIVE | `LoginServiceTest` + `AuthFlowIT` |
| OTP incorrecto / expirado / 3 fallos / sesión inválida | `MfaServiceTest` (parametrizado) |
| Reenvío de OTP exitoso / cooldown / max | `MfaServiceTest` + `MfaAttemptTrackerTest` |
| Refresh exitoso + rotación | `RefreshServiceTest` + `AuthFlowIT` |
| Refresh revocado → 401 | `AuthFlowIT` |
| Logout deja access token en blacklist | `LogoutServiceTest` + `AuthFlowIT` |
| Access token revocado rechazado | `AuthFlowIT` |
| Outlines email / OTP formato | unit + `AuthFlowIT` parametrizado |
| RNFs (latencias, no token en logs) | Inspección manual en HITO 6 (Kibana) |

---

## 10. Preguntas abiertas

| # | Pregunta | Propuesta |
|---|---|---|
| Q1 | Spec §5.1 paso 26: key Redis `refresh-token:{tokenId}`. ¿OK con D2 (key = `refresh-token:{sha256(token)}` en vez del token raw)? | **Resuelto 2026-05-19** — hash SHA-256 (ya reflejado en D2). |
| Q2 | Spec §12.3 menciona interceptor que dispara refresh en 401. ¿Single-flight o múltiples paralelas? | **Resuelto 2026-05-19** — single-flight (ya reflejado en D13). |
| Q3 | Logout (spec §5.2.2) requiere `Authorization: Bearer`. Si el access token ya expiró, ¿permitir logout sin token o estricto? | **Resuelto 2026-05-19** — estricto como spec. El interceptor JWT (D13) hace refresh transparente antes del logout; si también expiró el refresh, la cookie se limpia en el próximo `/refresh` fallido. Cero código extra. |
| Q4 | El bundle es grande. ¿Un solo PR o partir en 2 (Backend + Frontend)? | **Resuelto 2026-05-19** — un solo PR (cohesión funcional). Misma justificación que HU-F01: se documenta el tamaño en las notas del PR. |

---

## 11. Cronograma (post-recorte D18)

Estimación post-cut: **~1.5 días** (Día 2 + media tarde de Día 3). Lo recortado eran ~2 services + 1 controller + 3 excepciones + 2 components (TokenBlacklist, RefreshTokenStore) + 1 hook frontend (useLogout) + tests asociados + la complejidad del single-flight refresh.

Lo que queda en el bundle (post-D18):
- Backend: JwtService impl + JwtAuthenticationFilter impl + LoginService + MfaService + 3 controllers (Login, Mfa) + 2 trackers Redis + TempSessionManager + OtpGenerator + MailNotifier refactor + 2 templates email + 7 excepciones + handlers
- Frontend: AuthContext + useAuth + 3 hooks (useLogin, useMFAVerify, useMFAResend) + jwtInterceptor simple (401→/login) + ProtectedRoute + 5 componentes + 3 pages + tests

Margen de Sprint 1: **D2 + D3 medio** + D3 medio para HU-F04+F20 + D4 para HU-F06 + D5 buffer/estabilización. Apretado pero viable.

---

## 12. Definition of Done de este bundle

Se considera terminado cuando estén verdes todas las casillas de SPEC §15 y, además:
- 9 lotes implementados, HITOs 1-7 alcanzados
- `mvn verify` verde con Redis service en CI
- `npm run lint && test && build` verde
- E2E manual: registro → login → OTP en MailHog → MFA verify → /dashboard → logout → no puedo volver a /dashboard sin re-login
- PR abierto con plantilla CONVENTIONS §4.1 + checklist DoD
- APRENDIZAJES.md con sección Día 2-3 ([[feedback-actualizar-aprendizajes]])
