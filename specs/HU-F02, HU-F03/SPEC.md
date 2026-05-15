# spec.md — Inicio de sesión con autenticación multifactor por email

---

## 1. Metadatos

| Campo | Valor |
|---|---|
| ID(s) de HU | HU-F02 (BT-5 en Jira), HU-F03 (BT-6 en Jira) |
| Sprint | 1 |
| Prioridad MoSCoW | Must |
| Estado | Ready |
| Autor | *[Tu nombre]* |
| Fecha creación | 2026-05-08 |
| Última actualización | 2026-05-08 |
| Versión spec | 1.0 |
| Día estimado del ROADMAP | Día 2 |

---

## 2. Historia(s) de usuario

### HU-F02 — Iniciar sesión

**Como** inversionista, comisionista, administrador, responsable legal o miembro de junta directiva, **quiero** iniciar sesión con mis credenciales, **para** acceder a las funcionalidades del sistema según mi rol.

### HU-F03 — Verificar código MFA

**Como** inversionista, comisionista, administrador, responsable legal o miembro de junta directiva, **quiero** verificar mi identidad mediante un código MFA, **para** completar mi autenticación y acceder al sistema con un nivel de seguridad reforzado.

### Resumen unificado

Ambas HUs forman un **flujo de autenticación de dos pasos**: el usuario presenta credenciales (email + password), el sistema valida y emite un OTP de 6 dígitos al email, el usuario presenta el OTP, y al verificarse correctamente el sistema emite un par de tokens JWT (access + refresh) que habilitan acceso autenticado al resto del sistema. El bundle también cubre los flujos auxiliares de reenvío de OTP, refresco de tokens y cierre de sesión.

> **Nota sobre roles:** la HU-F02 menciona los cinco roles del sistema. En el MVP, el único rol creable vía registro público es `INVESTOR` (ver HU-F01). El endpoint de login es agnóstico al rol — funciona idénticamente para todos. Los otros roles requieren creación por Administrador, fuera del alcance del MVP.

---

## 3. Contexto y dependencias

### Por qué importa

Es el segundo flujo crítico del MVP y la puerta de entrada a cualquier funcionalidad autenticada (trading, portafolio, perfil). Es la primera materialización completa de TAC-S1 (Autenticar actores con MFA), TAC-S3 (Revocar acceso por intentos fallidos) y la base para RBAC en todo el sistema. Todo endpoint posterior asume que esta autenticación funciona.

### Dependencias técnicas

- **HU-F01 Registrarse** — debe existir al menos un usuario válido en `app.users` para iniciar sesión
- **Día 0 (Bootstrap)** — Redis, MailHog, backend operacionales
- **Spring Security configurado** con `BCryptPasswordEncoder` y filtros JWT (skeleton del Día 0)

### Features que dependen de esta

- **Todas las HUs posteriores del MVP**. Sin login no hay autorización, sin autorización no hay acceso a trading, portafolio, dashboard, perfil, suscripción
- HU-F08 Recuperar contraseña (post-MVP) — comparte infraestructura de OTP por email

---

## 4. Actores y precondiciones

### Actores involucrados

| Actor | Rol del sistema | Participación |
|---|---|---|
| Usuario registrado | INVESTOR (en MVP) | Iniciador |
| Sistema BloomTrade | — | Receptor / procesador |

### Precondiciones del sistema

- El usuario existe en `app.users` con estado `ACTIVE`
- Redis está disponible para almacenar OTPs, sesiones temporales, contadores de intentos, refresh tokens y blacklist
- MailHog está disponible para envío del OTP
- El usuario no tiene sesión JWT vigente (si la tiene, el frontend redirige a `/dashboard`)
- El usuario no tiene un bloqueo activo (`lockout:{userId}` ausente en Redis)

### Datos requeridos en el sistema

- Configuración Spring Security con `BCryptPasswordEncoder` activo
- Variable de entorno `JWT_SECRET` (mínimo 256 bits) configurada
- Plantilla de email `otp.html` disponible en classpath del backend
- Redis con configuración por defecto (puerto 6379)

---

## 5. Flujos

### 5.1 Flujo principal — login + MFA exitoso

**Precondiciones específicas:** Ver §4

#### Paso 1: Login con credenciales

1. Usuario navega a `/login` en el frontend
2. Sistema muestra formulario con campos email y password
3. Usuario completa email y password, presiona "Iniciar sesión"
4. Frontend valida formato básico (email RFC 5322, password no vacío)
5. Frontend envía `POST /api/v1/auth/login` con credentials
6. Backend valida formato (Bean Validation)
7. Backend consulta `app.users` por email (case-insensitive)
8. Backend verifica que el usuario existe y `estado = ACTIVE`
9. Backend consulta Redis `lockout:{userId}` — si existe, devuelve 423 Locked
10. Backend valida password con `BCryptPasswordEncoder.matches()`
11. Backend genera `tempSessionId` (UUID v4) y OTP (6 dígitos numéricos aleatorios)
12. Backend almacena en Redis:
    - `temp-session:{tempSessionId}` = `{userId, email, role, createdAt}`, TTL 5 min
    - `otp:{tempSessionId}` = `"123456"` (string), TTL 5 min
    - `mfa:attempts:{tempSessionId}` = `0`, TTL 5 min
    - `mfa:resends:{tempSessionId}` = `0`, TTL 5 min
13. Backend resetea el contador `login:attempts:{userId}` en Redis (DELETE)
14. Backend dispara envío de OTP vía NotificationService → MailHog (asíncrono)
15. Backend emite `LOGIN_ATTEMPT` con `result=ALLOWED` a AuditService
16. Backend responde 200 con `{ tempSessionId, expiresInSeconds: 300 }`
17. Frontend almacena `tempSessionId` en memoria (NO en localStorage) y navega a `/mfa-verify`

#### Paso 2: Verificación de OTP

18. Frontend muestra pantalla con 6 inputs para el OTP y temporizador de 5 min
19. Usuario recibe email en MailHog UI con el código
20. Usuario ingresa el código y presiona "Verificar"
21. Frontend envía `POST /api/v1/auth/mfa/verify` con `{ tempSessionId, code }`
22. Backend consulta `temp-session:{tempSessionId}` — si no existe, devuelve 401 (expirado o inválido)
23. Backend consulta `otp:{tempSessionId}` y compara con el código provisto
24. Backend valida que el código coincide
25. Backend genera tokens:
    - **Access Token (JWT)**: payload `{ sub: userId, role, jti: UUID }`, firmado HS256, exp 15 min
    - **Refresh Token**: string aleatorio de 64 chars (URL-safe base64)
26. Backend almacena `refresh-token:{refreshTokenId}` = `{userId, createdAt, rotationCount: 0}`, TTL 7 días en Redis
27. Backend invalida la sesión temporal: DELETE `temp-session:{tempSessionId}`, `otp:{tempSessionId}`, `mfa:attempts:{tempSessionId}`, `mfa:resends:{tempSessionId}`
28. Backend emite `MFA_VERIFIED` a AuditService
29. Backend responde 200 con `{ accessToken, expiresIn: 900, user: { id, email, nombreCompleto, rol } }` y setea cookie `refreshToken` (HttpOnly, Secure, SameSite=Strict, Path=/api/v1/auth/refresh, Max-Age=604800)
30. Frontend almacena access token en memoria (AuthContext)
31. Frontend redirige a `/dashboard`

**Postcondiciones:**
- Usuario tiene access token JWT vigente en memoria del frontend
- Usuario tiene refresh token en cookie HttpOnly
- `refresh-token:{tokenId}` registrado en Redis con TTL 7 días
- Sesión temporal y OTP eliminados de Redis
- Eventos `LOGIN_ATTEMPT` (ALLOWED) y `MFA_VERIFIED` indexados en ElasticSearch
- Email con OTP visible en MailHog UI

### 5.2 Flujos alternativos

#### 5.2.1 Refresco de access token

**Cuándo se activa:** El access token está próximo a expirar (frontend detecta <2 min restantes) o devolvió 401 con código `TOKEN_EXPIRED`

1. Frontend envía `POST /api/v1/auth/refresh` (la cookie `refreshToken` viaja automáticamente)
2. Backend lee la cookie `refreshToken`
3. Backend consulta `refresh-token:{tokenId}` en Redis
4. Backend valida que existe y obtiene `userId`
5. Backend genera nuevo access token JWT (15 min)
6. Backend **rota** el refresh token: genera nuevo string aleatorio, DELETE el viejo, almacena el nuevo en Redis con TTL 7 días (preserva `rotationCount + 1`)
7. Backend emite `TOKEN_REFRESHED` a AuditService
8. Backend responde 200 con `{ accessToken, expiresIn: 900 }` y setea cookie nueva
9. Frontend actualiza access token en AuthContext

**Postcondiciones específicas:**
- El refresh token viejo es inválido (DELETE en Redis)
- El nuevo refresh token tiene TTL renovado a 7 días desde este momento

#### 5.2.2 Cierre de sesión (logout)

**Cuándo se activa:** Usuario presiona "Cerrar sesión" en la app

1. Frontend envía `POST /api/v1/auth/logout` con header `Authorization: Bearer {accessToken}` y la cookie viaja automáticamente
2. Backend lee el access token (header) y el refresh token (cookie)
3. Backend extrae el `jti` del access token y lo agrega a blacklist: `revoked:{jti}` = `true`, TTL = tiempo restante del token
4. Backend hace DELETE del refresh token: `refresh-token:{refreshTokenId}` en Redis
5. Backend emite `LOGOUT` a AuditService
6. Backend responde 204 No Content y emite header `Set-Cookie: refreshToken=; Max-Age=0` para limpiar la cookie
7. Frontend limpia el AuthContext y redirige a `/login`

#### 5.2.3 Reenvío de OTP

**Cuándo se activa:** Usuario presiona "Reenviar código" en la pantalla de MFA

1. Frontend envía `POST /api/v1/auth/mfa/resend` con `{ tempSessionId }`
2. Backend consulta `temp-session:{tempSessionId}` — si no existe, devuelve 401
3. Backend consulta `mfa:resends:{tempSessionId}` — si >= 3, invalida sesión temporal y devuelve 429 con código `MAX_RESENDS_EXCEEDED`
4. Backend consulta `mfa:resend-cooldown:{tempSessionId}` — si existe, devuelve 429 con código `RESEND_COOLDOWN_ACTIVE` y header `Retry-After` indicando segundos restantes
5. Backend genera nuevo OTP y sobrescribe `otp:{tempSessionId}` con TTL 5 min (reinicia el temporizador del OTP, pero NO el de la sesión temporal)
6. Backend incrementa `mfa:resends:{tempSessionId}`
7. Backend setea `mfa:resend-cooldown:{tempSessionId}` = `1` con TTL 30s
8. Backend dispara nuevo email vía NotificationService
9. Backend emite `MFA_RESEND_REQUESTED` a AuditService
10. Backend responde 200 con `{ expiresInSeconds: 300, resendsRemaining: N }`

### 5.3 Flujos de error

#### 5.3.1 Credenciales inválidas (email o password incorrecto)

**Cuándo se dispara:** El email no existe en `app.users`, O el password no coincide tras BCrypt comparison
**Respuesta del sistema:** HTTP 401 Unauthorized con código `INVALID_CREDENTIALS` y mensaje genérico `"Credenciales inválidas"`. **Nunca se distingue entre "email no existe" y "password incorrecto"** (previene account enumeration).
**Estado final:** Si el email existe, se incrementa `login:attempts:{userId}` en Redis (TTL 1 hora desde el último intento). Si el email NO existe, no se incrementa nada (sería un canal lateral de detección).
**Evento de auditoría:** `LOGIN_ATTEMPT` con `result=DENIED`, `details.reason="INVALID_CREDENTIALS"`, `details.attemptedEmail` (para análisis forense)

#### 5.3.2 Tercera credencial fallida — cuenta bloqueada AHORA

**Cuándo se dispara:** `login:attempts:{userId}` alcanza valor 3 tras este intento fallido
**Respuesta del sistema:** HTTP 401 Unauthorized con código `INVALID_CREDENTIALS` (mismo que 5.3.1 — el usuario no sabe que justo se bloqueó porque podría ser un atacante)
**Estado final:**
- Se setea `lockout:{userId}` = `true` con TTL 900s (15 min)
- Se mantiene `login:attempts:{userId}` para registro forense
- Se envía email de notificación de bloqueo al usuario (vía NotificationService → MailHog)
**Eventos de auditoría:**
- `LOGIN_ATTEMPT` con `result=DENIED`, `details.reason="INVALID_CREDENTIALS"`
- `ACCOUNT_LOCKED` con `details.reason="MAX_LOGIN_ATTEMPTS"`, `details.lockDurationSeconds=900`

#### 5.3.3 Login intentado con cuenta YA bloqueada

**Cuándo se dispara:** `lockout:{userId}` existe en Redis al momento del login attempt
**Respuesta del sistema:** HTTP 423 Locked con código `ACCOUNT_LOCKED` y mensaje `"Cuenta bloqueada temporalmente por demasiados intentos fallidos. Intenta de nuevo en X minutos."` (donde X = ceil(TTL/60))
**Estado final:** Sin cambios (NO se incrementa el contador porque ya está bloqueada)
**Evento de auditoría:** `LOGIN_ATTEMPT` con `result=DENIED`, `details.reason="ACCOUNT_LOCKED"`

#### 5.3.4 Cuenta en estado distinto de ACTIVE

**Cuándo se dispara:** Usuario existe pero `estado` es `BLOCKED` o `SUSPENDED`
**Respuesta del sistema:** HTTP 403 Forbidden con código `ACCOUNT_NOT_ACTIVE` y mensaje genérico `"Tu cuenta no está activa. Contacta al administrador."`
**Estado final:** Sin cambios
**Evento de auditoría:** `LOGIN_ATTEMPT` con `result=DENIED`, `details.reason="ACCOUNT_NOT_ACTIVE"`, `details.accountStatus`

#### 5.3.5 Código OTP incorrecto

**Cuándo se dispara:** El código provisto no coincide con `otp:{tempSessionId}` en Redis
**Respuesta del sistema:** HTTP 400 Bad Request con código `MFA_INVALID_CODE` y mensaje `"Código incorrecto"`
**Estado final:**
- Se incrementa `mfa:attempts:{tempSessionId}` en Redis
- La sesión temporal y el OTP permanecen activos hasta agotar intentos o expirar
**Evento de auditoría:** `MFA_FAILED` con `details.reason="INVALID_CODE"`, `details.attemptNumber`

#### 5.3.6 Código OTP expirado

**Cuándo se dispara:** `otp:{tempSessionId}` no existe en Redis (TTL expiró) pero `temp-session:{tempSessionId}` sí existe (caso raro de race condition entre TTLs) — O el OTP fue invalidado tras un resend pero el usuario envió el código viejo
**Respuesta del sistema:** HTTP 400 Bad Request con código `MFA_CODE_EXPIRED` y mensaje `"El código ha expirado. Por favor solicita uno nuevo."`
**Estado final:** Sin cambios (no incrementa intentos porque no fue un intento real)
**Evento de auditoría:** `MFA_FAILED` con `details.reason="CODE_EXPIRED"`

#### 5.3.7 Tres intentos fallidos de MFA

**Cuándo se dispara:** `mfa:attempts:{tempSessionId}` alcanza valor 3
**Respuesta del sistema:** HTTP 403 Forbidden con código `MFA_SESSION_INVALIDATED` y mensaje `"Demasiados intentos. Por favor inicia sesión de nuevo."`
**Estado final:**
- Se invalida toda la sesión temporal: DELETE `temp-session:{tempSessionId}`, `otp:{tempSessionId}`, `mfa:attempts:{tempSessionId}`, `mfa:resends:{tempSessionId}`, `mfa:resend-cooldown:{tempSessionId}`
- El usuario debe regresar al login (paso 1)
**Evento de auditoría:** `MFA_SESSION_INVALIDATED` con `details.reason="MAX_ATTEMPTS"`

#### 5.3.8 Sesión temporal expirada o inexistente

**Cuándo se dispara:** El usuario envía `tempSessionId` pero `temp-session:{tempSessionId}` no existe en Redis (TTL expiró o nunca existió)
**Respuesta del sistema:** HTTP 401 Unauthorized con código `TEMP_SESSION_INVALID` y mensaje `"Tu sesión ha expirado. Por favor inicia sesión de nuevo."`
**Estado final:** Sin cambios
**Evento de auditoría:** `MFA_FAILED` con `details.reason="SESSION_EXPIRED"`

#### 5.3.9 Reenvío de OTP en cooldown

**Cuándo se dispara:** Usuario solicita resend antes de que pasen los 30s del último resend
**Respuesta del sistema:** HTTP 429 Too Many Requests con código `RESEND_COOLDOWN_ACTIVE`, mensaje `"Espera X segundos antes de solicitar otro código"`, header `Retry-After: X`
**Estado final:** Sin cambios
**Evento de auditoría:** No se emite (ruido de UI)

#### 5.3.10 Máximo de reenvíos alcanzado

**Cuándo se dispara:** Usuario intenta resend cuando `mfa:resends:{tempSessionId}` ya es 3
**Respuesta del sistema:** HTTP 429 Too Many Requests con código `MAX_RESENDS_EXCEEDED` y mensaje `"Has alcanzado el máximo de reenvíos. Por favor inicia sesión de nuevo."`
**Estado final:** Sesión temporal invalidada completamente (igual que 5.3.7)
**Evento de auditoría:** `MFA_SESSION_INVALIDATED` con `details.reason="MAX_RESENDS"`

#### 5.3.11 Refresh token inválido o revocado

**Cuándo se dispara:** El refresh token de la cookie no existe en Redis (rotación previa o logout)
**Respuesta del sistema:** HTTP 401 Unauthorized con código `REFRESH_TOKEN_INVALID` y mensaje `"Sesión expirada. Por favor inicia sesión de nuevo."`. Setea `Set-Cookie` para limpiar la cookie.
**Estado final:** Sin cambios en Redis (el token ya no existía)
**Evento de auditoría:** `TOKEN_REFRESH_FAILED` con `details.reason="INVALID_OR_REVOKED"`

#### 5.3.12 Access token en blacklist (revocado por logout)

**Cuándo se dispara:** Una request autenticada incluye un access token cuyo `jti` está en `revoked:{jti}` en Redis
**Respuesta del sistema:** HTTP 401 Unauthorized con código `TOKEN_REVOKED`
**Estado final:** Sin cambios
**Evento de auditoría:** `ACCESS_DENIED` con `details.reason="TOKEN_REVOKED"`

#### 5.3.13 Error técnico (BD inaccesible, Redis caído, etc.)

**Cuándo se dispara:** Cualquier excepción no esperada durante el flujo
**Respuesta del sistema:** HTTP 500 Internal Server Error con código `INTERNAL_ERROR`
**Estado final:** Indeterminado por naturaleza del error; el flujo se aborta
**Evento de auditoría:** `LOGIN_ATTEMPT` o `MFA_FAILED` con `details.reason="TECHNICAL_ERROR"`, `details.errorClass`

---

## 6. Contratos de datos

### 6.1 Endpoints nuevos

#### 6.1.1 `POST /api/v1/auth/login`

**Propósito:** Paso 1 del flujo de login. Valida credenciales y dispara envío de OTP.

**Auth requerido:** No

```yaml
paths:
  /api/v1/auth/login:
    post:
      summary: Inicia el flujo de autenticación con email y password
      tags: [Authentication]
      security: []
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/LoginRequest'
            example:
              email: "juan.perez@example.com"
              password: "SecurePass123"
      responses:
        '200':
          description: Credenciales válidas, OTP enviado al email del usuario
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/LoginResponse'
              example:
                tempSessionId: "7f3a2c1b-9e4d-4f6e-8a7d-2b9c1e5f8a3b"
                expiresInSeconds: 300
        '401':
          description: Credenciales inválidas o sesión expirada
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
        '403':
          description: Cuenta no activa (BLOCKED, SUSPENDED)
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
        '423':
          description: Cuenta bloqueada por intentos fallidos previos
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'

components:
  schemas:
    LoginRequest:
      type: object
      required: [email, password]
      properties:
        email:
          type: string
          format: email
          maxLength: 254
        password:
          type: string
          minLength: 1
          maxLength: 100
    LoginResponse:
      type: object
      properties:
        tempSessionId:
          type: string
          format: uuid
          description: Identificador opaco de la sesión temporal de login. El frontend lo usa para el endpoint /mfa/verify.
        expiresInSeconds:
          type: integer
          description: Segundos hasta que la sesión temporal expire (siempre 300 = 5 min al emitirse)
```

**Códigos de error específicos:**

| Código | HTTP | Cuándo |
|---|---|---|
| `INVALID_CREDENTIALS` | 401 | Email o password incorrecto (mensaje genérico) |
| `ACCOUNT_NOT_ACTIVE` | 403 | `estado != ACTIVE` |
| `ACCOUNT_LOCKED` | 423 | `lockout:{userId}` existe en Redis |
| `VALIDATION_FAILED` | 400 | Email malformado o password ausente |

#### 6.1.2 `POST /api/v1/auth/mfa/verify`

**Propósito:** Paso 2 del flujo de login. Valida el OTP y emite tokens JWT.

**Auth requerido:** No (autoriza con `tempSessionId`)

```yaml
paths:
  /api/v1/auth/mfa/verify:
    post:
      summary: Verifica el código OTP y emite tokens de acceso
      tags: [Authentication]
      security: []
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/MfaVerifyRequest'
            example:
              tempSessionId: "7f3a2c1b-9e4d-4f6e-8a7d-2b9c1e5f8a3b"
              code: "123456"
      responses:
        '200':
          description: OTP válido, sesión iniciada
          headers:
            Set-Cookie:
              schema:
                type: string
                example: "refreshToken=abc...; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth; Max-Age=604800"
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/MfaVerifyResponse'
              example:
                accessToken: "eyJhbGciOiJIUzI1NiJ9..."
                expiresIn: 900
                user:
                  id: "550e8400-e29b-41d4-a716-446655440000"
                  email: "juan.perez@example.com"
                  nombreCompleto: "Juan Pérez García"
                  rol: "INVESTOR"
        '400':
          description: Código incorrecto o expirado
        '401':
          description: Sesión temporal expirada o inexistente
        '403':
          description: Demasiados intentos, sesión invalidada

components:
  schemas:
    MfaVerifyRequest:
      type: object
      required: [tempSessionId, code]
      properties:
        tempSessionId:
          type: string
          format: uuid
        code:
          type: string
          pattern: '^\d{6}$'
          description: Código OTP de 6 dígitos
    MfaVerifyResponse:
      type: object
      properties:
        accessToken:
          type: string
          description: JWT firmado HS256, válido 15 min
        expiresIn:
          type: integer
          description: Segundos hasta expiración del access token (siempre 900)
        user:
          $ref: '#/components/schemas/UserSummary'
    UserSummary:
      type: object
      properties:
        id: { type: string, format: uuid }
        email: { type: string, format: email }
        nombreCompleto: { type: string }
        rol: { type: string, enum: [INVESTOR, BROKER, ADMIN, LEGAL, BOARD] }
```

**Códigos de error específicos:**

| Código | HTTP | Cuándo |
|---|---|---|
| `MFA_INVALID_CODE` | 400 | Código no coincide |
| `MFA_CODE_EXPIRED` | 400 | OTP expiró o fue reemplazado por resend |
| `TEMP_SESSION_INVALID` | 401 | Sesión temporal expiró o nunca existió |
| `MFA_SESSION_INVALIDATED` | 403 | 3 intentos fallidos consecutivos |

#### 6.1.3 `POST /api/v1/auth/mfa/resend`

**Propósito:** Reenviar el OTP a un usuario con sesión temporal activa.

**Auth requerido:** No (autoriza con `tempSessionId`)

```yaml
paths:
  /api/v1/auth/mfa/resend:
    post:
      summary: Genera y envía un nuevo OTP
      tags: [Authentication]
      security: []
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [tempSessionId]
              properties:
                tempSessionId:
                  type: string
                  format: uuid
            example:
              tempSessionId: "7f3a2c1b-9e4d-4f6e-8a7d-2b9c1e5f8a3b"
      responses:
        '200':
          description: Nuevo OTP enviado
          content:
            application/json:
              schema:
                type: object
                properties:
                  expiresInSeconds: { type: integer, example: 300 }
                  resendsRemaining: { type: integer, example: 2 }
        '401':
          description: Sesión temporal expirada
        '429':
          description: Cooldown activo o máximo de reenvíos alcanzado
          headers:
            Retry-After:
              schema: { type: integer }
              description: Segundos a esperar (solo para RESEND_COOLDOWN_ACTIVE)
```

**Códigos de error específicos:**

| Código | HTTP | Cuándo |
|---|---|---|
| `TEMP_SESSION_INVALID` | 401 | Sesión expirada |
| `RESEND_COOLDOWN_ACTIVE` | 429 | Han pasado <30s desde el último resend |
| `MAX_RESENDS_EXCEEDED` | 429 | Ya se hicieron 3 reenvíos |

#### 6.1.4 `POST /api/v1/auth/refresh`

**Propósito:** Rotar tokens JWT usando el refresh token de la cookie.

**Auth requerido:** Cookie `refreshToken` válida

```yaml
paths:
  /api/v1/auth/refresh:
    post:
      summary: Rota el par de tokens (access + refresh)
      tags: [Authentication]
      security: []
      parameters:
        - in: cookie
          name: refreshToken
          required: true
          schema: { type: string }
      responses:
        '200':
          description: Nuevo par de tokens emitido
          headers:
            Set-Cookie:
              schema: { type: string }
              description: Nueva cookie refreshToken con rotación
          content:
            application/json:
              schema:
                type: object
                properties:
                  accessToken: { type: string }
                  expiresIn: { type: integer, example: 900 }
        '401':
          description: Refresh token inválido, expirado o revocado
```

**Códigos de error específicos:**

| Código | HTTP | Cuándo |
|---|---|---|
| `REFRESH_TOKEN_INVALID` | 401 | Token no existe en Redis (revocado, expirado, o nunca existió) |

#### 6.1.5 `POST /api/v1/auth/logout`

**Propósito:** Cerrar sesión revocando access token y refresh token.

**Auth requerido:** `Authorization: Bearer {accessToken}` + cookie `refreshToken`

```yaml
paths:
  /api/v1/auth/logout:
    post:
      summary: Cierra la sesión revocando ambos tokens
      tags: [Authentication]
      security:
        - bearerAuth: []
      responses:
        '204':
          description: Sesión cerrada exitosamente
          headers:
            Set-Cookie:
              schema: { type: string }
              description: "refreshToken=; Max-Age=0 (limpia la cookie)"
        '401':
          description: Access token inválido o ausente
```

### 6.2 Endpoints modificados

No aplica. Estos endpoints son nuevos.

### 6.3 Esquemas de datos compartidos

**`UserSummary`** se introduce aquí y se reutilizará en cualquier endpoint que devuelva información básica del usuario autenticado (ej: HU-F04 Configurar perfil).

**Header `Authorization: Bearer {jwt}`** se establece como estándar para todos los endpoints autenticados del proyecto a partir de esta spec.

---

## 7. Cambios en base de datos

**No aplica.** Todo el estado de autenticación (sesiones temporales, OTPs, contadores de intentos, refresh tokens, blacklist de tokens revocados) vive **exclusivamente en Redis con TTLs**. Razones:

- Las sesiones de auth son intrínsecamente efímeras
- Redis maneja TTLs nativamente — no necesitamos cron jobs de limpieza
- No hay requerimiento de auditabilidad histórica sobre las sesiones (la auditoría se hace vía eventos en ElasticSearch)
- Simplifica la BD: ningún schema nuevo para esta feature

Si en el futuro se requiere persistencia (ej: ver "sesiones activas" en gestión de perfil), se puede agregar una tabla `app.user_sessions` que duplique los refresh tokens. Fuera del MVP.

---

## 8. Mapeo arquitectónico

### 8.1 Módulos involucrados

| Módulo | Rol | Componentes específicos tocados |
|---|---|---|
| AuthService | Iniciador | `LoginController`, `LoginService`, `MfaService`, `TokenService`, `MFAValidator`, `LoginAttemptTracker`, `OtpGenerator`, `JwtIssuer`, `TempSessionManager` |
| AuditService | Notificado | `AuditLogger` (múltiples eventos, ver §9.1) |
| NotificationService | Notificado | `OtpEmailDispatcher`, plantilla `otp.html`, plantilla `account-locked.html` |

### 8.2 Interfaces consumidas

| Interfaz | Módulo que la expone | Para qué se usa aquí |
|---|---|---|
| `IAudit` | AuditService | Emitir todos los eventos de auth de §9.1 |
| `INotification` | NotificationService | Enviar OTP por email y notificación de bloqueo |
| `ICacheStore` | (Redis, externo) | Gestionar OTPs, sesiones temporales, intentos, refresh tokens, blacklist |

### 8.3 Interfaces expuestas

| Interfaz | Quién la consumirá | Contrato |
|---|---|---|
| `IAuthentication` | TradingService, PortfolioService, y todos los módulos autenticados | Método `validateToken(jwt) → AuthenticatedUser` para usar en filtros de Spring Security |

> Esta interfaz se introduce aquí y será consumida por todos los endpoints autenticados del proyecto. La validación del JWT incluye: verificar firma HS256, verificar que el `jti` no esté en `revoked:{jti}` de Redis, verificar `exp`.

### 8.4 Tácticas de Bass aplicadas

| Táctica | ID | Cómo se materializa en esta feature |
|---|---|---|
| Autenticar actores | TAC-S1 | OTP de 6 dígitos por email + password con BCrypt |
| Revocar acceso | TAC-S3 | Bloqueo automático tras 3 intentos fallidos + blacklist de tokens en logout |
| Mantener registro de auditoría | TAC-S4 | 10+ event types emitidos a ElasticSearch para todo el ciclo |
| Encapsular | TAC-M3 | Política de OTP encapsulada en `OtpGenerator`; emisión JWT en `JwtIssuer`; gestión de intentos en `LoginAttemptTracker` |

---

## 9. Efectos colaterales

### 9.1 Eventos de auditoría

| `event_type` | Trigger | Campos extra en `details` |
|---|---|---|
| `LOGIN_ATTEMPT` | Cualquier intento de login (paso 1), exitoso o fallido | `{ attemptedEmail, reason, ipOrigin, userAgent }` (reason solo en denied) |
| `MFA_VERIFIED` | OTP validado correctamente | `{ tempSessionDurationMs }` |
| `MFA_FAILED` | OTP incorrecto, expirado, o sesión temporal inválida | `{ reason: "INVALID_CODE" \| "CODE_EXPIRED" \| "SESSION_EXPIRED", attemptNumber }` |
| `MFA_RESEND_REQUESTED` | Nuevo OTP solicitado vía /resend | `{ resendNumber }` |
| `MFA_SESSION_INVALIDATED` | 3 intentos MFA fallidos o 3 resends agotados | `{ reason: "MAX_ATTEMPTS" \| "MAX_RESENDS" }` |
| `ACCOUNT_LOCKED` | Cuenta bloqueada por 3 logins fallidos | `{ reason: "MAX_LOGIN_ATTEMPTS", lockDurationSeconds: 900 }` |
| `TOKEN_REFRESHED` | Refresh token rotado exitosamente | `{ rotationCount }` |
| `TOKEN_REFRESH_FAILED` | Refresh token inválido o revocado | `{ reason: "INVALID_OR_REVOKED" }` |
| `LOGOUT` | Usuario cierra sesión | `{ accessTokenJti, refreshTokenIdHash }` |
| `ACCESS_DENIED` | Request con token revocado o inválido | `{ reason, jti }` |

### 9.2 Notificaciones

| Trigger | Canal | Asunto / Plantilla | Contenido resumido |
|---|---|---|---|
| Login con credenciales válidas | Email (MailHog) | "Tu código de acceso a BloomTrade" / `otp.html` | OTP de 6 dígitos prominente, mensaje de vigencia 5 min, advertencia si no fue tú |
| Cuenta bloqueada por 3 intentos | Email (MailHog) | "Tu cuenta ha sido bloqueada temporalmente" / `account-locked.html` | Notificación del bloqueo, duración (15 min), instrucción de contacto si no fue tú |

### 9.3 Cambios en caché Redis

| Key pattern | Operación | TTL | Justificación |
|---|---|---|---|
| `temp-session:{tempSessionId}` | SET / GET / DELETE | 300s (5 min) | Sesión temporal entre login y MFA |
| `otp:{tempSessionId}` | SET / GET / DELETE | 300s (5 min, se resetea al reenviar) | Código OTP de un solo uso |
| `mfa:attempts:{tempSessionId}` | INCR / DELETE | 300s (atado a la sesión temporal) | Contador de intentos de MFA |
| `mfa:resends:{tempSessionId}` | INCR / DELETE | 300s (atado a la sesión temporal) | Contador de reenvíos solicitados |
| `mfa:resend-cooldown:{tempSessionId}` | SET con TTL | 30s | Cooldown entre reenvíos |
| `login:attempts:{userId}` | INCR / DELETE | 3600s (1 hora desde último intento) | Contador para detectar 3 fallos consecutivos |
| `lockout:{userId}` | SET con TTL / GET | 900s (15 min) | Marca de bloqueo activo |
| `refresh-token:{tokenId}` | SET / GET / DELETE | 604800s (7 días) | Refresh token con metadata |
| `revoked:{jti}` | SET con TTL | Hasta `exp` del access token (≤900s) | Blacklist de access tokens revocados por logout |

### 9.4 Llamadas a APIs externas

| API externa | Endpoint | Adapter | Cuándo se invoca |
|---|---|---|---|
| MailHog (SMTP) | `mailhog:1025` | Spring Mail | Tras login exitoso (paso 1), tras resend de OTP, tras bloqueo de cuenta |

---

## 10. Atributos de calidad aplicables

### 10.1 Escenarios de calidad referenciados

| ID escenario | Atributo | Cómo esta feature lo soporta |
|---|---|---|
| ESC-S1 | Seguridad | 3 intentos fallidos → bloqueo automático + notificación email en <1 min. El bloqueo se aplica al tercer intento dentro del mismo proceso de login (sin polling externo) y la notificación se dispara asíncronamente inmediatamente tras setear `lockout:{userId}` |
| ESC-S3 | Seguridad | Orden con sesión robada → rechazada en <1s mediante blacklist de tokens revocados en Redis. Cualquier token con `jti` en `revoked:{jti}` se rechaza en el filtro JWT |
| ESC-D2 | Disponibilidad | Fallo del módulo de auth → restaurado en <2 min. El módulo es stateless (todo el estado en Redis), por lo que un restart del backend container restaura el servicio inmediatamente sin pérdida de sesiones activas |

### 10.2 Constraints específicos de esta feature

| Constraint | Medida |
|---|---|
| Latencia del endpoint /login (incluyendo BCrypt comparison) | <800ms p95 |
| Latencia del endpoint /mfa/verify (sin contar envío de email previo) | <300ms p95 |
| Latencia del endpoint /refresh | <100ms p95 |
| Email con OTP entregado a MailHog | <2s desde respuesta del /login |

---

## 11. Criterios de aceptación

### 11.1 Escenarios de aceptación

```gherkin
Funcionalidad: Inicio de sesión con autenticación multifactor

  Antecedentes:
    Dado que el sistema BloomTrade está corriendo
    Y existe un usuario en app.users con email "juan@example.com",
      password (hash de "SecurePass123"), estado ACTIVE, rol INVESTOR
    Y Redis está vacío de claves de auth para este usuario
    Y MailHog está disponible

  Escenario: Login y MFA exitoso (flujo principal)
    Dado un usuario no autenticado en la página /login
    Cuando ingresa email "juan@example.com" y password "SecurePass123" y envía el formulario
    Entonces el sistema responde 200 con un tempSessionId
    Y se almacena temp-session, otp, mfa:attempts=0 y mfa:resends=0 en Redis
    Y se elimina login:attempts:{userId} en Redis (si existía)
    Y se envía email con OTP de 6 dígitos visible en MailHog
    Y se emite LOGIN_ATTEMPT con result=ALLOWED a ElasticSearch
    Y el frontend redirige a /mfa-verify

    Cuando el usuario ingresa el OTP recibido y envía
    Entonces el sistema responde 200 con accessToken JWT, expiresIn=900, y datos del usuario
    Y se setea cookie refreshToken HttpOnly Secure SameSite=Strict
    Y se almacena refresh-token:{tokenId} en Redis con TTL 7 días
    Y se eliminan temp-session, otp, mfa:attempts, mfa:resends de Redis
    Y se emite MFA_VERIFIED a ElasticSearch
    Y el frontend redirige a /dashboard

  Escenario: Credenciales inválidas
    Cuando se envía POST /api/v1/auth/login con email="juan@example.com" y password="ContraseñaIncorrecta"
    Entonces el sistema responde 401 con código INVALID_CREDENTIALS
    Y se incrementa login:attempts:{userId} a 1
    Y se emite LOGIN_ATTEMPT con result=DENIED y reason="INVALID_CREDENTIALS"
    Y NO se envía email de OTP

  Escenario: Email no registrado
    Cuando se envía POST /api/v1/auth/login con email="noexiste@example.com" y password="cualquiera"
    Entonces el sistema responde 401 con código INVALID_CREDENTIALS (mismo mensaje que credencial inválida real)
    Y NO se incrementa ningún contador (prevenir account enumeration)
    Y se emite LOGIN_ATTEMPT con result=DENIED y reason="INVALID_CREDENTIALS"

  Escenario: Tercer intento fallido activa el bloqueo
    Dado que login:attempts:{userId} = 2 en Redis (por dos intentos fallidos previos)
    Cuando el usuario envía POST /api/v1/auth/login con email correcto y password incorrecto
    Entonces el sistema responde 401 con código INVALID_CREDENTIALS
    Y se setea lockout:{userId}=true con TTL 900s en Redis
    Y se envía email "Tu cuenta ha sido bloqueada temporalmente" visible en MailHog
    Y se emiten LOGIN_ATTEMPT (DENIED) y ACCOUNT_LOCKED a ElasticSearch

  Escenario: Intento de login con cuenta ya bloqueada
    Dado que lockout:{userId} existe en Redis con TTL 600s restantes
    Cuando el usuario envía POST /api/v1/auth/login con credenciales (cualesquiera)
    Entonces el sistema responde 423 con código ACCOUNT_LOCKED
    Y el mensaje indica "Intenta de nuevo en 10 minutos"
    Y NO se incrementa login:attempts (la cuenta ya está bloqueada)
    Y se emite LOGIN_ATTEMPT con reason="ACCOUNT_LOCKED"

  Escenario: Bloqueo se libera automáticamente tras 15 minutos
    Dado que lockout:{userId} expiró (TTL llegó a 0)
    Cuando el usuario envía POST /api/v1/auth/login con credenciales correctas
    Entonces el sistema responde 200 con tempSessionId (flujo normal)
    Y se elimina login:attempts:{userId} de Redis (reset implícito en login exitoso)

  Escenario: Login exitoso resetea contador de intentos
    Dado que login:attempts:{userId} = 2 en Redis
    Cuando el usuario envía login con credenciales correctas
    Entonces el sistema responde 200
    Y se elimina login:attempts:{userId} de Redis

  Escenario: Cuenta BLOCKED no puede iniciar sesión
    Dado que el usuario tiene estado="BLOCKED" en app.users
    Cuando se envía POST /api/v1/auth/login con credenciales correctas
    Entonces el sistema responde 403 con código ACCOUNT_NOT_ACTIVE
    Y se emite LOGIN_ATTEMPT con reason="ACCOUNT_NOT_ACTIVE"
    Y NO se genera OTP ni sesión temporal

  Escenario: Código OTP incorrecto
    Dado una sesión temporal activa con OTP="123456" en Redis
    Cuando se envía POST /api/v1/auth/mfa/verify con tempSessionId válido y code="000000"
    Entonces el sistema responde 400 con código MFA_INVALID_CODE
    Y se incrementa mfa:attempts:{tempSessionId} a 1
    Y la sesión temporal y el OTP permanecen activos
    Y se emite MFA_FAILED con reason="INVALID_CODE" y attemptNumber=1

  Escenario: Tres intentos MFA fallidos invalidan la sesión temporal
    Dado una sesión temporal con mfa:attempts=2 en Redis
    Cuando el usuario envía un código incorrecto por tercera vez
    Entonces el sistema responde 403 con código MFA_SESSION_INVALIDATED
    Y se eliminan todas las claves de la sesión temporal (temp-session, otp, mfa:attempts, mfa:resends)
    Y se emite MFA_SESSION_INVALIDATED con reason="MAX_ATTEMPTS"

  Escenario: Sesión temporal expirada
    Dado que han pasado 5 minutos desde el login y temp-session:{tempSessionId} no existe en Redis
    Cuando se envía POST /api/v1/auth/mfa/verify con ese tempSessionId
    Entonces el sistema responde 401 con código TEMP_SESSION_INVALID
    Y se emite MFA_FAILED con reason="SESSION_EXPIRED"

  Escenario: Reenvío exitoso de OTP
    Dado una sesión temporal activa con mfa:resends=0 y sin cooldown
    Cuando el usuario envía POST /api/v1/auth/mfa/resend
    Entonces el sistema responde 200 con resendsRemaining=2
    Y se sobrescribe otp:{tempSessionId} con un código nuevo
    Y se incrementa mfa:resends:{tempSessionId} a 1
    Y se setea mfa:resend-cooldown:{tempSessionId} con TTL 30s
    Y se envía nuevo email a MailHog con el código nuevo
    Y se emite MFA_RESEND_REQUESTED con resendNumber=1

  Escenario: Reenvío durante cooldown
    Dado mfa:resend-cooldown:{tempSessionId} existe con 20s restantes
    Cuando el usuario envía POST /api/v1/auth/mfa/resend
    Entonces el sistema responde 429 con código RESEND_COOLDOWN_ACTIVE
    Y el header Retry-After contiene "20"
    Y NO se genera nuevo OTP

  Escenario: Cuarto reenvío invalida la sesión
    Dado que mfa:resends:{tempSessionId} = 3 en Redis
    Cuando el usuario envía POST /api/v1/auth/mfa/resend
    Entonces el sistema responde 429 con código MAX_RESENDS_EXCEEDED
    Y se invalida la sesión temporal completa
    Y se emite MFA_SESSION_INVALIDATED con reason="MAX_RESENDS"

  Escenario: Refresco exitoso de tokens
    Dado un usuario autenticado con refresh token válido en cookie
    Cuando el frontend envía POST /api/v1/auth/refresh
    Entonces el sistema responde 200 con un nuevo accessToken
    Y el viejo refresh-token:{tokenId} es eliminado de Redis
    Y un nuevo refresh-token:{nuevoTokenId} es almacenado en Redis con TTL 7 días
    Y se setea nueva cookie refreshToken
    Y se emite TOKEN_REFRESHED a ElasticSearch

  Escenario: Refresh token revocado tras logout
    Dado un usuario que acaba de hacer logout (su refresh token fue eliminado de Redis)
    Cuando intenta refrescar con esa misma cookie
    Entonces el sistema responde 401 con código REFRESH_TOKEN_INVALID
    Y la cookie se limpia (Set-Cookie con Max-Age=0)
    Y se emite TOKEN_REFRESH_FAILED

  Escenario: Logout exitoso
    Dado un usuario autenticado con access token y refresh token vigentes
    Cuando envía POST /api/v1/auth/logout
    Entonces el sistema responde 204 No Content
    Y el jti del access token queda en revoked:{jti} en Redis con TTL=expiración restante
    Y el refresh-token:{tokenId} es eliminado de Redis
    Y la cookie refreshToken se limpia
    Y se emite LOGOUT a ElasticSearch

  Escenario: Access token revocado es rechazado
    Dado un access token cuyo jti está en revoked:{jti} en Redis
    Cuando se envía cualquier request autenticada con ese token
    Entonces el sistema responde 401 con código TOKEN_REVOKED
    Y se emite ACCESS_DENIED con reason="TOKEN_REVOKED"

  Esquema del escenario: Validación de formato de email en login
    Cuando se envía POST /api/v1/auth/login con email=<valor> y password="cualquiera123"
    Entonces el sistema responde <httpStatus> con código <errorCode>

    Ejemplos:
      | valor              | httpStatus | errorCode                |
      | (vacío)            | 400        | VALIDATION_REQUIRED      |
      | "no-arroba"        | 400        | VALIDATION_INVALID_EMAIL |
      | "valid@example.com"| 401        | INVALID_CREDENTIALS      |

  Esquema del escenario: Validación de formato del código OTP
    Cuando se envía POST /api/v1/auth/mfa/verify con tempSessionId válido y code=<valor>
    Entonces el sistema responde <httpStatus> con código <errorCode>

    Ejemplos:
      | valor       | httpStatus | errorCode               |
      | (vacío)     | 400        | VALIDATION_REQUIRED     |
      | "12345"     | 400        | VALIDATION_INVALID_OTP  |
      | "1234567"   | 400        | VALIDATION_INVALID_OTP  |
      | "abc123"    | 400        | VALIDATION_INVALID_OTP  |
      | "999999"    | 400        | MFA_INVALID_CODE        |
```

### 11.2 Criterios no funcionales verificables

| Criterio | Medida | Cómo se verifica |
|---|---|---|
| Endpoint /login responde en <800ms p95 | 100 requests concurrentes | JMeter ad-hoc o `time curl` |
| Endpoint /mfa/verify responde en <300ms p95 | 100 requests | JMeter |
| Endpoint /refresh responde en <100ms p95 | 100 requests | JMeter |
| Email con OTP entregado en <2s | Inspección manual: enviar login, contar tiempo hasta aparecer en MailHog | Cronómetro |
| Access token NUNCA aparece en logs de auditoría | Búsqueda en Kibana de regex `eyJ[A-Za-z0-9_-]+` | Inspección |
| Password NUNCA aparece en logs | Búsqueda en Kibana del password de un usuario de test | Inspección |

---

## 12. UI y experiencia

### 12.1 Páginas / vistas afectadas

#### Página `/login`

**Propósito:** Formulario de credenciales (paso 1 del flujo de autenticación)

**Acceso:** Pública. Si hay sesión activa, redirige a `/dashboard`.

**Componente principal:** `LoginPage.tsx`

**Elementos visibles:**

| Elemento | Tipo | Comportamiento |
|---|---|---|
| Título "Iniciar sesión" | Heading | Estático |
| Campo Email | Input email | Required, validación on blur |
| Campo Password | Input password con toggle visibilidad | Required |
| Botón "Iniciar sesión" | Submit | Habilitado si ambos campos válidos. Spinner durante submit. |
| Link "¿No tienes cuenta? Regístrate" | Link | Navega a `/register` |

**Estados:**

| Estado | UI |
|---|---|
| Idle | Formulario vacío, botón deshabilitado |
| Submitting | Botón con spinner "Verificando..." |
| Success | Redirección a `/mfa-verify` |
| Error 401 INVALID_CREDENTIALS | Banner rojo "Credenciales inválidas" (genérico) |
| Error 423 ACCOUNT_LOCKED | Banner rojo "Cuenta bloqueada. Intenta de nuevo en X minutos." |
| Error 403 ACCOUNT_NOT_ACTIVE | Banner rojo "Tu cuenta no está activa. Contacta al administrador." |
| Error 500 | Banner rojo "Error temporal del servidor" |

#### Página `/mfa-verify`

**Propósito:** Verificación del código OTP (paso 2)

**Acceso:** Solo accesible si hay `tempSessionId` en memoria (estado de navegación de React Router). Si no hay tempSessionId, redirige a `/login`.

**Componente principal:** `MFAVerifyPage.tsx`

**Elementos visibles:**

| Elemento | Tipo | Comportamiento |
|---|---|---|
| Título "Verifica tu identidad" | Heading | Estático |
| Subtítulo | Texto | "Ingresa el código de 6 dígitos que enviamos a {email enmascarado}" |
| 6 inputs OTP | Custom OTP input | Cada input acepta 1 dígito, auto-focus al siguiente. Paste de 6 dígitos auto-llena todos. |
| Temporizador | Texto con cuenta regresiva | Muestra "Expira en MM:SS". Cuando llega a 00:00, deshabilita verificar y muestra "Solicita un nuevo código" |
| Botón "Verificar" | Submit | Habilitado cuando los 6 dígitos están completos |
| Botón "Reenviar código" | Action button | Deshabilitado durante cooldown (30s). Muestra "Reenviar en XXs" durante cooldown. Después de 3 reenvíos: deshabilitado permanentemente con mensaje. |
| Link "Volver al login" | Link | Limpia tempSessionId y navega a /login |

**Estados:**

| Estado | UI |
|---|---|
| Idle | Inputs vacíos, botón verificar deshabilitado, temporizador corriendo |
| Submitting | Botón con spinner |
| Success | Toast verde "Acceso autorizado" + redirección a /dashboard |
| Error MFA_INVALID_CODE | Mensaje rojo bajo los inputs "Código incorrecto. Intentos restantes: X" |
| Error MFA_CODE_EXPIRED | Mensaje "El código expiró. Solicita uno nuevo." + botón reenviar destacado |
| Error MFA_SESSION_INVALIDATED | Banner rojo "Demasiados intentos. Inicia sesión de nuevo." + redirección a /login tras 3s |
| Resend success | Toast verde "Nuevo código enviado" + reset del temporizador |
| Resend en cooldown | El botón muestra "Reenviar en 28s" en lugar de habilitarse |

### 12.2 Componentes nuevos a crear

| Componente | Ubicación | Propósito |
|---|---|---|
| `LoginPage` | `src/pages/LoginPage.tsx` | Página completa de login |
| `LoginForm` | `src/features/auth/components/LoginForm.tsx` | Formulario controlado |
| `MFAVerifyPage` | `src/pages/MFAVerifyPage.tsx` | Página completa de MFA |
| `OTPInput` | `src/components/OTPInput.tsx` | Input de 6 dígitos con auto-advance (reutilizable) |
| `Countdown` | `src/components/Countdown.tsx` | Temporizador visual MM:SS reutilizable |
| `ResendButton` | `src/features/auth/components/ResendButton.tsx` | Botón con cooldown y conteo de reenvíos |
| `ProtectedRoute` | `src/components/ProtectedRoute.tsx` | Wrapper de rutas autenticadas (introducido aquí, reutilizado en TODA ruta posterior) |
| `AppHeader` | `src/components/AppHeader.tsx` | Header con info usuario + botón logout (post-login) |

### 12.3 Hooks o utilidades nuevas

| Item | Ubicación | Propósito |
|---|---|---|
| `useLogin` | `src/features/auth/hooks/useLogin.ts` | Mutación React Query para POST /auth/login |
| `useMFAVerify` | `src/features/auth/hooks/useMFAVerify.ts` | Mutación para POST /auth/mfa/verify |
| `useMFAResend` | `src/features/auth/hooks/useMFAResend.ts` | Mutación para POST /auth/mfa/resend |
| `useLogout` | `src/features/auth/hooks/useLogout.ts` | Mutación para POST /auth/logout + limpieza de contexto |
| `useAuth` | `src/features/auth/hooks/useAuth.ts` | Hook combinado: estado actual del usuario, isAuthenticated, accessToken |
| `AuthProvider` + `AuthContext` | `src/features/auth/context/AuthContext.tsx` | Provider de React Context con: user, accessToken, login(), logout(), refreshToken(). Estado vive en memoria, NO en localStorage. |
| `jwtInterceptor` | `src/lib/apiClient.ts` (extensión) | Axios request interceptor: añade `Authorization: Bearer {token}` automáticamente. Response interceptor: si 401 + TOKEN_EXPIRED, intenta refresh transparente y reintenta la request original. Si refresh falla, navega a /login. |
| `loginSchema`, `mfaSchema` | `src/features/auth/schemas/` | Zod schemas de validación |

### 12.4 Cambios de routing

| Ruta | Componente | Acceso |
|---|---|---|
| `/login` | `LoginPage` | Pública (redirige a /dashboard si autenticado) |
| `/mfa-verify` | `MFAVerifyPage` | Pública pero condicional (requiere tempSessionId en state) |
| `/dashboard` | `DashboardPage` (placeholder por ahora) | Protegida con `ProtectedRoute` |

> A partir de esta spec, toda nueva ruta autenticada se monta dentro de `<ProtectedRoute>`. `ProtectedRoute` verifica `isAuthenticated` del AuthContext y redirige a `/login` si no está autenticado.

---

## 13. Fuera de alcance de esta spec

- **"Recordar este dispositivo"** para saltar MFA en logins subsecuentes — fuera del MVP (decisión registrada al inicio de esta spec)
- **Bloqueo escalado** (15min → 1h → 24h → permanente) — el MVP solo implementa bloqueo simple de 15 min
- **MFA por SMS o WhatsApp vía Twilio** — el MVP solo usa email; Twilio queda configurado para post-MVP
- **MFA por TOTP (Google Authenticator)** — fuera del MVP
- **Detección de sesiones simultáneas sospechosas** (login desde dos países en 5 min) — fuera del MVP
- **Gestión de sesiones activas en perfil** (ver "estoy logueado en X dispositivos", botón "cerrar todas las sesiones") — fuera del MVP
- **Recuperación de contraseña** — HU-F08, post-MVP
- **Cambio de password desde perfil** — HU-F04, no en este bundle
- **Rate limiting por IP** (no por usuario) — fuera del MVP
- **Bloqueo permanente con desbloqueo manual por Admin** — fuera del MVP

---

## 14. Preguntas abiertas

Ninguna. Todas las decisiones críticas resueltas previo a la redacción de esta spec.

---

## 15. Definition of Done específica de esta spec

- ☐ Los 5 endpoints (`/login`, `/mfa/verify`, `/mfa/resend`, `/refresh`, `/logout`) documentados en Swagger UI con todos los códigos de respuesta
- ☐ Todos los escenarios Gherkin de §11 traducidos a tests automatizados (unitarios + integración con Testcontainers para Redis y BD)
- ☐ Los 10 `event_type` de §9.1 verificables en Kibana después de ejercitar los flujos correspondientes
- ☐ Plantillas de email `otp.html` y `account-locked.html` creadas en `backend/src/main/resources/templates/email/`
- ☐ Página `/login` funcional en `http://localhost:5173/login`
- ☐ Página `/mfa-verify` funcional con flujo completo
- ☐ `AuthContext` y `ProtectedRoute` implementados y verificables: intentar acceder a `/dashboard` sin auth redirige a `/login`
- ☐ Axios interceptor de JWT funcionando: una request a endpoint protegido con token expirado dispara `/refresh` transparente y reintenta
- ☐ Logout deja el access token usado en blacklist de Redis (verificable: el siguiente uso devuelve 401 TOKEN_REVOKED)
- ☐ Bloqueo de cuenta tras 3 intentos verificable manualmente con MailHog (email "Tu cuenta ha sido bloqueada" llega)
- ☐ Auto-unlock tras 15 min verificable (esperar TTL en Redis o setear TTL bajo en perfil de test)
- ☐ Rotación de refresh token verificable: tras un `/refresh`, el viejo refresh token devuelve 401 si se intenta reusar
- ☐ `JWT_SECRET` configurado vía variable de entorno (no en código, no en repositorio)
- ☐ Variable `JWT_SECRET` documentada en `.env.example`

---

## Changelog

| Versión | Fecha | Cambio | Razón |
|---|---|---|---|
| 1.0 | 2026-05-08 | Versión inicial | Segunda spec del MVP (bundle HU-F02 + HU-F03) |
