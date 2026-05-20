# tasks.md — HU-F02 + HU-F03 Login + MFA (bundle)

> Descomposición granular del `plan.md` post-recorte D18 (SDD Paso 3).
> Cadencia: lotes lógicos, validación en HITOs (no tras cada archivo).
> Rama: `feat/HU-F02-F03-login-mfa`. Commits con `refs HU-F02 HU-F03 specs/HU-F02-F03-login-mfa/SPEC.md` + `Co-authored-by: Claude`.

Leyenda: ☐ pendiente · ◐ en progreso · ☑ hecho · ✗ cancelado/diferido

---

## Lote A — Fundaciones backend (JWT + Redis + filter)

- ☑ **T1.1** `config/RedisConfig.java` — decisión: usar `StringRedisTemplate` autoconfigurado; no se crea `RedisConfig.java` propio.
- ☑ **T1.2** `auth/security/AuthenticatedUser.java` — record `(UUID userId, String role)`.
- ☑ **T1.3** `auth/security/JwtService.java` — IMPL completa (Día 0 era stub): `generateAccessToken(userId, role) → String`, `validate(token) → Claims`, `extractJti(token)`. Lanza `TokenExpiredException` / `TokenInvalidException`. Secret leído de `${JWT_SECRET}` / `jwt.secret`; ACCESS_TTL_MINUTES de `${JWT_ACCESS_TTL_MINUTES:15}` / `jwt.access-ttl-minutes`.
- ☑ **T1.4** `auth/exception/{TokenExpiredException, TokenInvalidException}.java`.
- ☑ **T1.5** `config/JwtAuthenticationFilter.java` — IMPL completa (Día 0 era passthrough): extrae `Authorization: Bearer`, valida con `JwtService`, popula `SecurityContext` con `UsernamePasswordAuthenticationToken(AuthenticatedUser, null, [ROLE_<role>])`. Token expirado/inválido → 401 específico. **Sin check de blacklist** (D18: no hay TokenBlacklist por ahora).
- ☑ **T1.6** `shared/web/GlobalExceptionHandler.java` MODIFICADO: handlers para `TokenExpiredException` → 401 `TOKEN_EXPIRED`, `TokenInvalidException` → 401 `TOKEN_INVALID`.
- ☑ **T1.7** `validation-messages.properties` MODIFICADO: + códigos de auth (todos los de D9: VALIDATION_INVALID_OTP, INVALID_CREDENTIALS, ACCOUNT_LOCKED, etc.). **← HITO 1** (`mvnw compile` verde 2026-05-20).

## Lote B — Notification refactor + templates

- ☐ **T2.1** `notification/Notifier.java` MODIFICADO: + `void sendOtpEmail(OtpEmailCommand)` y `void sendAccountLockedEmail(AccountLockedEmailCommand)`.
- ☐ **T2.2** `notification/dto/{OtpEmailCommand, AccountLockedEmailCommand}.java` — records.
- ☐ **T2.3** `notification/MailNotifier.java` — RENAME de `WelcomeEmailDispatcher.java`; implementa los 3 métodos. `sendWelcomeEmail` queda idéntico en comportamiento. Los nuevos métodos siguen el mismo patrón (`@Async` + JavaMailSender + Thymeleaf + audit on failure).
- ☐ **T2.4** `resources/templates/email/otp.html` — Thymeleaf con OTP destacado tipográficamente, variables `{nombreCompleto, otpCode, expiresInMinutes}`.
- ☐ **T2.5** `resources/templates/email/account-locked.html` — variables `{nombreCompleto, lockDurationMinutes}`.
- ☐ **T2.6** `audit/AuditEventType.java` MODIFICADO: + `OTP_EMAIL_FAILED, ACCOUNT_LOCKED_EMAIL_FAILED` (paralelo a `WELCOME_EMAIL_FAILED`).
- ☐ **T2.7** `unit/notification/WelcomeEmailDispatcherTest.java` → RENAME a `MailNotifierTest.java`, agregar tests para los 3 métodos (welcome sigue verde + 2 nuevos happy/failure).

## Lote C — Login flow

- ☐ **T3.1** `auth/repository/UserRepository.java` MODIFICADO: + `Optional<User> findByEmailIgnoreCase(String)`.
- ☐ **T3.2** `auth/ratelimit/LoginAttemptTracker.java` — encapsula `login:attempts:{userId}` (INCR + TTL 1h) y `lockout:{userId}` (SET + TTL 15 min). Métodos: `recordFailed(userId) → int (newCount)`, `isLocked(userId) → boolean`, `lockoutSecondsRemaining(userId) → long`, `reset(userId)`, `lock(userId)`.
- ☐ **T3.3** `auth/session/{TempSessionManager, TempSessionData, OtpGenerator}.java`. `TempSessionData` record `(String userId, String email, String role, Instant createdAt)`. `OtpGenerator.generate() → String` (6 dígitos, SecureRandom). `OtpGenerator.matches(provided, stored)` con `MessageDigest.isEqual`. `TempSessionManager` SET/GET/DELETE de `temp-session:{id}` + `otp:{id}` + counters.
- ☐ **T3.4** `auth/dto/{LoginRequest, LoginResponse, UserSummary}.java`. LoginRequest con `@NotBlank/@Email`; LoginResponse `(String tempSessionId, int expiresInSeconds)`.
- ☐ **T3.5** `auth/exception/{InvalidCredentialsException, AccountLockedException, AccountNotActiveException}.java` + handlers en `GlobalExceptionHandler` (mapean 401 `INVALID_CREDENTIALS`, 423 `ACCOUNT_LOCKED` con `lockoutSecondsRemaining`, 403 `ACCOUNT_NOT_ACTIVE`).
- ☐ **T3.6** `auth/service/LoginService.java` — todo spec §5.1 paso 1-16. `@Transactional(readOnly=true)` para el `findByEmailIgnoreCase`. Emite `LOGIN_ATTEMPT` (ALLOWED/DENIED) + `ACCOUNT_LOCKED` cuando aplica. Dispara `Notifier.sendOtpEmail` asíncrono. Captura `DataAccessException` → audit + relanza (mismo patrón HU-F01).
- ☐ **T3.7** `auth/controller/LoginController.java` — `POST /api/v1/auth/login` con `@Valid` + OpenAPI annotations (200/400/401/403/423/500).
- ☐ **T3.8** `config/SecurityConfig.java` MODIFICADO: + `permitAll` para `POST /api/v1/auth/login`. **← HITO 2** (manual: `curl POST /login` con creds válidas devuelve `{tempSessionId, 300}` + OTP en MailHog).

## Lote D — MFA flow (verify + resend + emisión de access token)

- ☐ **T4.1** `auth/ratelimit/MfaAttemptTracker.java` — encapsula `mfa:attempts:{tempSessionId}`, `mfa:resends:{tempSessionId}`, `mfa:resend-cooldown:{tempSessionId}`. Métodos: `recordFailed(tempSessionId) → int`, `recordResend(tempSessionId) → int`, `isOnCooldown(tempSessionId) → boolean`, `cooldownSecondsRemaining(tempSessionId) → long`.
- ☐ **T4.2** `auth/security/TokenIssuer.java` — método único `issueAccessToken(userId, role) → String` que delega en `JwtService`. (D18: solo access; no refresh).
- ☐ **T4.3** `auth/dto/{MfaVerifyRequest, MfaVerifyResponse, MfaResendRequest, MfaResendResponse}.java`. `MfaVerifyRequest` con `@Pattern("^\\d{6}$")` para code, message `VALIDATION_INVALID_OTP`. `MfaVerifyResponse(String accessToken, int expiresIn, UserSummary user)`.
- ☐ **T4.4** `auth/exception/{MfaInvalidCodeException, MfaCodeExpiredException, MfaSessionInvalidatedException, TempSessionInvalidException, ResendCooldownActiveException, MaxResendsExceededException}.java` + handlers (400 `MFA_INVALID_CODE`, 400 `MFA_CODE_EXPIRED`, 403 `MFA_SESSION_INVALIDATED`, 401 `TEMP_SESSION_INVALID`, 429 `RESEND_COOLDOWN_ACTIVE` con `Retry-After` header, 429 `MAX_RESENDS_EXCEEDED`).
- ☐ **T4.5** `auth/service/MfaService.java` — `verify(req)` (spec §5.1 paso 18-31) y `resend(req)` (spec §5.2.3). Eventos: `MFA_VERIFIED`, `MFA_FAILED`, `MFA_RESEND_REQUESTED`, `MFA_SESSION_INVALIDATED`.
- ☐ **T4.6** `auth/controller/MfaController.java` — `POST /api/v1/auth/mfa/verify` y `POST /api/v1/auth/mfa/resend` con OpenAPI.
- ☐ **T4.7** `config/SecurityConfig.java` MODIFICADO: + `permitAll` para los dos endpoints. **← HITO 3** (manual: flujo completo `curl login → MFA verify → JWT válido`).

## ✗ Lote E — Refresh + Logout — **DIFERIDO POR D18** (post-MVP)

No se implementa en este bundle. Mini-HU futura `HU-F0X-token-rotation-logout` cubrirá:
- `RefreshTokenStore` + `TokenBlacklist` en Redis
- `RefreshService` + `LogoutService` + `TokenController` con `/refresh` y `/logout`
- Cookie HttpOnly refresh + rotación + revoked:{jti}
- Frontend: `useLogout` con backend call + jwtInterceptor con single-flight refresh

## Lote F — Tests backend + CI Redis

- ☐ **T5.1** Unit: `JwtServiceTest` (emisión, validación, expiración, firma alterada).
- ☐ **T5.2** Unit: `OtpGeneratorTest` (formato, timing-safe).
- ☐ **T5.3** Unit: `LoginAttemptTrackerTest` + `MfaAttemptTrackerTest` (con `StringRedisTemplate` mockeado o un fake in-memory).
- ☐ **T5.4** Unit: `LoginServiceTest` (happy, INVALID_CREDENTIALS, ACCOUNT_LOCKED, ACCOUNT_NOT_ACTIVE, 3er intento setea lockout, reset en éxito).
- ☐ **T5.5** Unit: `MfaServiceTest` (verify happy, INVALID_CODE incrementa contador, CODE_EXPIRED, 3er fail invalida sesión, resend happy + cooldown + max).
- ☐ **T5.6** Unit: `TokenIssuerTest` (delegación correcta a JwtService).
- ☐ **T5.7** IT: `AuthFlowIT` — Postgres + Redis reales del compose, perfil `test`. Flujo: registrar usuario (helper) → POST `/login` → capturar OTP (lee directamente de Redis por simplicidad) → POST `/mfa/verify` → recibir JWT → request a endpoint protegido stub con `Authorization: Bearer` → 200 OK. Verifica audit events: `LOGIN_ATTEMPT(ALLOWED)`, `MFA_VERIFIED`.
- ☐ **T5.8** IT: `LockoutFlowIT` — 3 logins fallidos → 4° login devuelve 423 → email `account-locked` enviado (verifica `@MockBean Notifier`). Verifica audit `ACCOUNT_LOCKED`.
- ☐ **T5.9** IT: `OpenApiContractIT` MODIFICADO — agrega los 3 endpoints nuevos a la verificación de `/v3/api-docs`.
- ☐ **T5.10** `.github/workflows/ci.yml` MODIFICADO: + `service: redis` (redis:7-alpine puerto 6379:6379 + healthcheck `redis-cli ping`) + env `JWT_SECRET` generado al vuelo (`openssl rand -base64 64`). **← HITO 4** (`mvn verify` verde en CI).

## Lote G — Frontend infra (AuthContext + interceptor + ProtectedRoute)

- ☐ **T6.1** `src/types/api.ts` MODIFICADO: + `LoginRequest`, `LoginResponse`, `MfaVerifyRequest`, `MfaVerifyResponse`, `MfaResendRequest`, `MfaResendResponse`, `UserSummary`.
- ☐ **T6.2** `src/lib/messages.es.ts` MODIFICADO: + todos los códigos nuevos de D9 con mensajes ES.
- ☐ **T6.3** `src/features/auth/context/AuthContext.tsx` — `AuthProvider` + `useAuth` hook. State: `{user, accessToken, isAuthenticated}` en memoria (NO localStorage). Métodos: `setSession(token, user)`, `clearSession()`. Sin lógica de refresh (D18).
- ☐ **T6.4** `src/lib/apiClient.ts` MODIFICADO: + `jwtInterceptor` con dos partes:
  - request interceptor: si hay `accessToken` (vía un getter inyectado desde AuthProvider), añadir `Authorization: Bearer <token>`.
  - response interceptor: si 401 con `error === "TOKEN_EXPIRED"` o `"TOKEN_INVALID"`, limpia AuthContext y `window.location.assign('/login')`. (D18 simplificado: sin refresh transparente).
- ☐ **T6.5** `src/components/ProtectedRoute.tsx` — wrapper: si `!isAuthenticated` → `<Navigate to="/login" replace />`.
- ☐ **T6.6** `src/features/auth/hooks/useSession.ts` → BORRAR (lo reemplaza `useAuth`).
- ☐ **T6.7** `src/pages/RegisterPage.tsx` MODIFICADO: cambiar `useSession` → `useAuth`. Misma lógica del guard.
- ☐ **T6.8** `src/main.tsx` MODIFICADO: envolver `<App />` con `<AuthProvider>` dentro del `<QueryClientProvider>`.

## Lote H — Frontend pages (Login + MFA + Dashboard placeholder)

- ☐ **T7.1** `src/features/auth/schemas/{login.ts, mfa.ts}` — zod schemas. login: email + password required. mfa: tempSessionId UUID + code 6 dígitos.
- ☐ **T7.2** `src/features/auth/hooks/{useLogin, useMFAVerify, useMFAResend}.ts` — React Query mutations contra `apiClient`. `useMFAVerify` on-success llama `setSession(...)`.
- ☐ **T7.3** `src/components/Countdown.tsx` — recibe `expiresAt: Date`, renderiza "MM:SS" con `setInterval` cada segundo. Cleanup en unmount.
- ☐ **T7.4** `src/components/OTPInput.tsx` — 6 inputs `type=text inputMode=numeric maxLength=1`. Auto-focus al siguiente al tipear. Paste de 6 dígitos auto-llena todos. Emite `onChange(string)` cuando los 6 están completos.
- ☐ **T7.5** `src/features/auth/components/ResendButton.tsx` — botón con estado `idle / cooldown(secs) / disabled-maxed`. Llama `useMFAResend`; 429 RESEND_COOLDOWN_ACTIVE setea cooldown desde `Retry-After`; 429 MAX_RESENDS_EXCEEDED setea disabled-maxed.
- ☐ **T7.6** `src/features/auth/components/LoginForm.tsx` — RHF + zod. Submit → `useLogin.mutate({email, password})`. Banner para 401/403/423/500. Success: navega `/mfa-verify` con `{tempSessionId, email, expiresAt}` en `location.state`.
- ☐ **T7.7** `src/pages/LoginPage.tsx` — REEMPLAZA el stub HU-F01. Wrapper del `LoginForm`. Guard: si `isAuthenticated`, redirect `/dashboard`.
- ☐ **T7.8** `src/pages/MFAVerifyPage.tsx` — recibe `tempSessionId/email/expiresAt` de `location.state`; si falta, redirect `/login`. Renderiza `OTPInput` + `Countdown` + `ResendButton` + botón "Verificar". Llama `useMFAVerify`; success → `setSession(accessToken, user)` → navega `/dashboard`. Errores: MFA_INVALID_CODE muestra "Intentos restantes: N" (deriva del response), MFA_CODE_EXPIRED prompt resend, MFA_SESSION_INVALIDATED banner + redirect `/login` tras 3s.
- ☐ **T7.9** `src/components/AppHeader.tsx` — muestra `user.nombreCompleto` + botón "Cerrar sesión" que llama `clearSession()` + `navigate('/login')`. **No llama backend** (D18 soft logout).
- ☐ **T7.10** `src/pages/DashboardPage.tsx` — placeholder: `<AppHeader />` + `<h1>Bienvenido, {user.nombreCompleto}</h1>` + un texto "Próximamente: trading, portafolio, dashboard de mercado".
- ☐ **T7.11** `src/App.tsx` MODIFICADO: agrega rutas `/login`, `/mfa-verify`, `/dashboard` (esta última envuelta en `<ProtectedRoute>`). `*` redirige a `/login` si no autenticado o `/dashboard` si sí. **← HITO 5** (E2E manual: registro → login → MFA → dashboard).

## Lote I — Tests frontend + cierre

- ☐ **T8.1** `AuthContext.test.tsx` — provider expone state correcto, `setSession` actualiza, `clearSession` limpia.
- ☐ **T8.2** `LoginForm.test.tsx` — submit deshabilitado al inicio, válido habilita + dispara mutación, 401 muestra banner "Credenciales inválidas".
- ☐ **T8.3** `MFAVerifyPage.test.tsx` — sin `location.state` redirige a /login; 6 inputs auto-advance + paste; submit habilitado solo con 6 dígitos.
- ☐ **T8.4** `ProtectedRoute.test.tsx` — no auth → `<Navigate to="/login" />`; auth → renderiza children.
- ☐ **T8.5** `jwtInterceptor.test.ts` — request interceptor agrega `Authorization` cuando hay token; response 401 + TOKEN_EXPIRED limpia AuthContext + navega.
- ☐ **T8.6** Verificación DoD spec §15 ítems aplicables (los de refresh+logout quedan N/A por D18).
- ☐ **T8.7** `APRENDIZAJES.md` MODIFICADO: sección "Día 2-3 — HU-F02+F03" siguiendo el estilo Día 0/1 ([[feedback-actualizar-aprendizajes]]).
- ☐ **T8.8** PR `feat/HU-F02-F03-login-mfa` → `main` con plantilla CONVENTIONS §4.1. DoD marca explícitamente los ítems N/A por D18. **← HITO 6** (PR abierto + CI verde + listo para merge).

## Deuda nueva identificada (para post-bundle)

- **Mini-HU `HU-F0X-token-rotation-logout`** (D18 deferral): `/refresh` con rotación, `/logout` con blacklist, cookie HttpOnly refresh, `RefreshTokenStore`, `TokenBlacklist`, `useLogout`, `jwtInterceptor` con single-flight refresh.
- **CI: agregar Redis service** queda hecho en T5.10; verificar también que el step `Test (vitest)` del Día 1 sigue ejecutándose.
- **Limpiar `JWT_REFRESH_SECRET` de `.env.example`** (G2 del plan) — sin uso real, confunde.
