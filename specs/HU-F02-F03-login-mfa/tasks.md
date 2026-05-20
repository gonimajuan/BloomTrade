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

- ☑ **T2.1** `notification/Notifier.java` MODIFICADO: + `void sendOtpEmail(OtpEmailCommand)` y `void sendAccountLockedEmail(AccountLockedEmailCommand)`.
- ☑ **T2.2** `notification/dto/{OtpEmailCommand, AccountLockedEmailCommand}.java` — records.
- ☑ **T2.3** `notification/MailNotifier.java` — RENAME de `WelcomeEmailDispatcher.java`; implementa los 3 métodos. `sendWelcomeEmail` queda idéntico en comportamiento. Los nuevos métodos siguen el mismo patrón (`@Async` + JavaMailSender + Thymeleaf + audit on failure).
- ☑ **T2.4** `resources/templates/email/otp.html` — Thymeleaf con OTP destacado tipográficamente, variables `{nombreCompleto, otpCode, expiresInMinutes}`.
- ☑ **T2.5** `resources/templates/email/account-locked.html` — variables `{nombreCompleto, lockDurationMinutes}`.
- ☑ **T2.6** `audit/AuditEventType.java` MODIFICADO: + `OTP_EMAIL_FAILED, ACCOUNT_LOCKED_EMAIL_FAILED` (paralelo a `WELCOME_EMAIL_FAILED`).
- ☑ **T2.7** `unit/notification/WelcomeEmailDispatcherTest.java` → RENAME a `MailNotifierTest.java`, agregar tests para los 3 métodos (welcome sigue verde + 2 nuevos happy/failure). **← Lote B verde** (`compile` + `-Dtest=MailNotifierTest test` verdes 2026-05-20).

## Lote C — Login flow ✅ (cerrado 2026-05-20, commit `a55c553`)

- ☑ **T3.1** `auth/repository/UserRepository.java` MODIFICADO: + `Optional<User> findByEmailIgnoreCase(String)` sobre el índice funcional `idx_users_email_lower`.
- ☑ **T3.2** `auth/ratelimit/LoginAttemptTracker.java` — encapsula `login:attempts:{userId}` (INCR + TTL 1h) y `lockout:{userId}` (SET + TTL 15 min). Métodos: `recordFailed`, `isLocked`, `lockoutSecondsRemaining`, `reset`, `lock`.
- ☑ **T3.3** `auth/session/{TempSessionManager, TempSessionData, OtpGenerator}.java`. `TempSessionData` record `(userId, email, role, createdAt)`. `OtpGenerator.generate()` 6 dígitos SecureRandom + `matches` timing-safe (`MessageDigest.isEqual`). `TempSessionManager` con API completa `createSession/getSession/getOtp/replaceOtp/invalidate` (deja Lote D limpio).
- ☑ **T3.4** `auth/dto/{LoginRequest, LoginResponse, UserSummary}.java`. LoginRequest con `@NotBlank` + `@Pattern` admite vacío (no solapa con VALIDATION_REQUIRED, mismo truco HU-F01).
- ☑ **T3.5** `auth/exception/{InvalidCredentialsException, AccountLockedException, AccountNotActiveException}.java` + 3 handlers en `GlobalExceptionHandler` (401 INVALID_CREDENTIALS, 423 ACCOUNT_LOCKED con header `Retry-After` y mensaje "Intenta de nuevo en X minutos", 403 ACCOUNT_NOT_ACTIVE).
- ☑ **T3.6** `auth/service/LoginService.java` — flujo spec §5.1 pasos 1-16 con orden lookup → ACTIVE → lockout → password. Audit LOGIN_ATTEMPT(ALLOWED/DENIED) con `attemptedEmail` + `reason`; ACCOUNT_LOCKED al 3er fallo con `lockDurationSeconds=900`. `DataAccessException` audita TECHNICAL_ERROR y relanza (mismo patrón RegisterService).
- ☑ **T3.7** `auth/controller/LoginController.java` — `POST /api/v1/auth/login` con `@Valid` + OpenAPI 200/400/401/403/423/500.
- ☑ **T3.8** `config/SecurityConfig.java` MODIFICADO: + `permitAll` para login. **HITO 2 ✅** (`mvn clean compile` verde 2026-05-20, 57 source files).
- ☑ **AuditEventType.java** — extendido con `LOGIN_ATTEMPT` y `ACCOUNT_LOCKED`.

## Lote D — MFA flow ✅ (cerrado 2026-05-20)

- ☑ **T4.1** `auth/ratelimit/MfaAttemptTracker.java` — wrapper de `mfa:attempts:`, `mfa:resends:` (inicializadas por TempSessionManager, INCR aquí) y `mfa:resend-cooldown:` con TTL 30s. Métodos: `recordFailed`, `recordResend`, `getResendCount`, `isOnCooldown`, `cooldownSecondsRemaining`, `setCooldown`.
- ☑ **T4.2** `auth/security/TokenIssuer.java` — record `IssuedAccessToken(accessToken, expiresInSeconds)` empaquetando la llamada a `JwtService.generateAccessToken` + `accessTokenTtl()`.
- ☑ **T4.3** `auth/dto/{MfaVerifyRequest, MfaVerifyResponse, MfaResendRequest, MfaResendResponse}.java`. Patrón OTP `^(|\d{6})$` admite vacío para no solapar con VALIDATION_REQUIRED.
- ☑ **T4.4** 6 excepciones nuevas + handlers (400 MFA_INVALID_CODE con "Intentos restantes: N", 400 MFA_CODE_EXPIRED, 403 MFA_SESSION_INVALIDATED, 401 TEMP_SESSION_INVALID, 429 RESEND_COOLDOWN_ACTIVE con `Retry-After`, 429 MAX_RESENDS_EXCEEDED).
- ☑ **T4.5** `auth/service/MfaService.java` — `verify` (spec §5.1 paso 18-31, sin cookie por D18) y `resend` (§5.2.3). Audits MFA_VERIFIED con `tempSessionDurationMs`, MFA_FAILED con `reason` + `attemptNumber`, MFA_RESEND_REQUESTED con `resendNumber`, MFA_SESSION_INVALIDATED con `reason` (MAX_ATTEMPTS / MAX_RESENDS).
- ☑ **T4.6** `auth/controller/MfaController.java` — `POST /api/v1/auth/mfa/verify` y `/mfa/resend` en `/api/v1/auth/mfa` con OpenAPI completo.
- ☑ **T4.7** `config/SecurityConfig.java` MODIFICADO: + `permitAll` para `/mfa/verify` y `/mfa/resend`. **HITO 3 ✅** (`mvn clean compile` verde 2026-05-20, 71 source files).
- ☑ **AuditEventType.java** — extendido con `MFA_VERIFIED`, `MFA_FAILED`, `MFA_RESEND_REQUESTED`, `MFA_SESSION_INVALIDATED`.

## ✗ Lote E — Refresh + Logout — **DIFERIDO POR D18** (post-MVP)

No se implementa en este bundle. Mini-HU futura `HU-F0X-token-rotation-logout` cubrirá:
- `RefreshTokenStore` + `TokenBlacklist` en Redis
- `RefreshService` + `LogoutService` + `TokenController` con `/refresh` y `/logout`
- Cookie HttpOnly refresh + rotación + revoked:{jti}
- Frontend: `useLogout` con backend call + jwtInterceptor con single-flight refresh

## Lote F — Tests backend + CI Redis ✅ (cerrado 2026-05-20)

- ☑ **T5.1** Unit `JwtServiceTest` — 7 tests (emisión, validación, TTL, secret <32 bytes, firma alterada, token malformado). TTL expirado se prueba con `new JwtService(SECRET, -1)` (evita Thread.sleep).
- ☑ **T5.2** Unit `OtpGeneratorTest` — 6 tests (formato, distribución, matches timing-safe, null inputs).
- ☑ **T5.3** Unit `LoginAttemptTrackerTest` (8) + `MfaAttemptTrackerTest` (8) — mocks de `StringRedisTemplate` + `ValueOperations`.
- ☑ **T5.4** Unit `LoginServiceTest` — 7 tests cubriendo happy + 4 ramas DENIED + 3er fallo lockea + envía email + technical error.
- ☑ **T5.5** Unit `MfaServiceTest` — 9 tests (verify happy/SESSION_EXPIRED/CODE_EXPIRED/INVALID_CODE/3er fail invalida; resend happy/sin sesión/cooldown/max).
- ☑ **T5.6** Unit `TokenIssuerTest` — 1 test (delegación correcta).
- ☑ **T5.7** IT `AuthFlowIT` — 3 tests con Postgres + Redis reales. Registra usuario → POST `/login` → lee OTP de Redis vía `StringRedisTemplate` → POST `/mfa/verify` → JWT válido (validado con `jwtService.validate(token)`). Verifica audit LOGIN_ATTEMPT(ALLOWED) y MFA_VERIFIED.
- ☑ **T5.8** IT `LockoutFlowIT` — 3 logins fallidos → 4to login 423 → email account-locked enviado + audit ACCOUNT_LOCKED. `@MockBean Notifier`.
- ☑ **T5.9** IT `OpenApiContractIT` MODIFICADO — + 3 tests para `/login`, `/mfa/verify`, `/mfa/resend`. Removida exclusión de `RedisAutoConfiguration` (necesaria por los beans nuevos).
- ☑ **T5.10** `.github/workflows/ci.yml` MODIFICADO: + `service: redis:7-alpine` con healthcheck `redis-cli ping`. JWT_SECRET NO se inyecta como env (decisión: `application-test.yml` hardcodea el secret de testing — mata la necesidad de `openssl rand`). **HITO 4 ✅** (`mvn verify` BUILD SUCCESS 2026-05-20, 90 tests verdes: 78 unit + 12 IT).
- ☑ **Fixes colaterales descubiertos al correr `mvn verify` completo**:
  - `JwtService.java` placeholder `${JWT_SECRET:}` (default vacío en el inner) para que `BloomtradeApplicationTests.contextLoads` no falle por placeholder no resuelto.
  - `RegisterFlowIT.java` quita exclusión de Redis: los beans nuevos de auth requieren `StringRedisTemplate` desde el context.

## Lote G — Frontend infra ✅ (cerrado 2026-05-20)

- ☑ **T6.1** `src/types/api.ts` MODIFICADO: + 7 tipos nuevos (LoginRequest/Response, MfaVerify/Resend Request/Response, UserSummary). Reorganizado por HU.
- ☑ **T6.2** `src/lib/messages.es.ts` MODIFICADO: + 11 códigos nuevos de auth + 1 de token (INVALID_CREDENTIALS, ACCOUNT_LOCKED, MFA_INVALID_CODE, TEMP_SESSION_INVALID, RESEND_COOLDOWN_ACTIVE, MAX_RESENDS_EXCEEDED, TOKEN_EXPIRED, etc.).
- ☑ **T6.3** `src/features/auth/context/AuthContext.tsx` — `AuthProvider` + `useAuth`. State `{user, accessToken, isAuthenticated, setSession, clearSession}` en memoria. `useEffect` re-configura el interceptor del apiClient cuando cambia el token.
- ☑ **T6.4** `src/lib/apiClient.ts` MODIFICADO: + request interceptor (añade Bearer) + response interceptor (401 + TOKEN_EXPIRED/TOKEN_INVALID/TOKEN_REVOKED → `unauthorizedHandler`). API pública: `configureAuthInterceptor({getAccessToken, onUnauthorized})`.
- ☑ **T6.5** `src/components/ProtectedRoute.tsx` — Navigate a `/login` con `replace` + `state.from` (deep-link recovery futuro, costo cero).
- ☑ **T6.6** `src/features/auth/hooks/useSession.ts` — BORRADO. Promesa Q2 del plan HU-F01 cumplida.
- ☑ **T6.7** `src/pages/RegisterPage.tsx` MODIFICADO: `useSession().token` → `useAuth().isAuthenticated`.
- ☑ **T6.8** `src/main.tsx` MODIFICADO: `<AuthProvider>` envuelve `<App />` **dentro** de `<BrowserRouter>` (necesario para `useNavigate`) y dentro de `<QueryClientProvider>`.
- ☑ Verificación: `tsc --noEmit` verde, `vite build` verde (159 modules, 1.83s), vitest verde (9 tests existentes de HU-F01).
- ☑ Extensión adicional: `lib/errorParser.ts` agrega `retryAfter?: number` parseado del header `Retry-After` (Lote H lo consume).

## Lote H — Frontend pages ✅ (cerrado 2026-05-20, HITO 5 verde)

- ☑ **T7.1** `src/features/auth/schemas/{login.ts, mfa.ts}` — zod schemas con códigos SCREAMING_SNAKE como `message`.
- ☑ **T7.2** `src/features/auth/hooks/{useLogin, useMFAVerify, useMFAResend}.ts` — React Query mutations con `parseError`.
- ☑ **T7.3** `src/components/Countdown.tsx` — MM:SS con `setInterval`; `onExpire` one-shot vía flag local.
- ☑ **T7.4** `src/components/OTPInput.tsx` — 6 inputs `inputMode=numeric`, auto-focus al siguiente, paste de 6 dígitos, navegación Backspace/flechas, `value: string` canónico de 0-6 dígitos.
- ☑ **T7.5** `src/features/auth/components/ResendButton.tsx` — máquina `idle/cooldown/maxed`. Cooldown desde `retryAfter` del ParsedError; default 30s post-success.
- ☑ **T7.6** `src/features/auth/components/LoginForm.tsx` — RHF + zod. On success → navega `/mfa-verify` con `state = {tempSessionId, email, expiresAt: ISO}`.
- ☑ **T7.7** `src/pages/LoginPage.tsx` — REEMPLAZA stub HU-F01. Guard si ya autenticado.
- ☑ **T7.8** `src/pages/MFAVerifyPage.tsx` — guard de `location.state`; sin state → `/login`. Maneja MFA_INVALID_CODE / MFA_CODE_EXPIRED / MFA_SESSION_INVALIDATED (banner + redirect tras 3s) / TEMP_SESSION_INVALID. Email enmascarado para presentación.
- ☑ **T7.9** `src/components/AppHeader.tsx` — `nombreCompleto` + chip de rol + botón "Cerrar sesión" (D18 soft).
- ☑ **T7.10** `src/pages/DashboardPage.tsx` — placeholder con AppHeader + bienvenida.
- ☑ **T7.11** `src/App.tsx` MODIFICADO: rutas `/login`, `/mfa-verify`, `/dashboard` (ProtectedRoute); catch-all redirige según `isAuthenticated`. **HITO 5 ✅** E2E manual verde 2026-05-20.
- ☑ **Fixes de infra descubiertos al ejecutar HITO 5 en docker (latentes desde HU-F01/Lote A)**:
  - `docker-compose.yml` — agrega `JWT_SECRET` (con `:?` que aborta si vacío) + `JWT_ACCESS_TTL_MINUTES` al servicio `backend`. Sin esto el JwtService falla por `>=32 bytes` y el container hace restart loop.
  - `frontend/nginx.conf` — quita trailing slash de `proxy_pass http://backend:8080/`. Con `/` final nginx strippea el prefijo `/api/` y Spring Security devuelve 403 sin body (el frontend lo veía como NETWORK_ERROR).

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
