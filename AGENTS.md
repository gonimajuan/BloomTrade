# AGENTS.md — BloomTrade

> Instrucciones para asistentes de código (Codex CLI, Claude Code, Cursor, Aider, etc.).
> Este archivo es **cross-agente**. Complementa a `CLAUDE.md` — pese al nombre, esas reglas
> son universales del proyecto, no específicas de Claude.

---

## Lo primero en cada sesión (sin excepción)

1. **Leé `CLAUDE.md` completo.** Es la constitución del proyecto. Las reglas inviolables
   #1–#24 aplican a vos también.
2. **Leé los documentos maestros en este orden:** `ARCHITECTURE.md` → `STACK.md` →
   `CONVENTIONS.md` → `ROADMAP.md`. No empieces a codear sin haberlos pasado.
3. **Leé la HU activa** (ver §"Trabajo activo" abajo): `specs/<HU>/SPEC.md` →
   `specs/<HU>/plan.md` → `specs/<HU>/tasks.md`.
4. **Confirmá branch + git status** antes de tocar nada.
5. **Saludá brevemente** describiendo: qué es el proyecto, qué arquitectura, qué HU/lote
   está activo. Esperá una indicación humana antes de codear (CLAUDE.md "Lo primero").

---

## Trabajo activo (actualizar al final de cada sesión)

| Campo | Valor |
|---|---|
| Branch | `feat/HU-F02-F03-login-mfa` |
| HU | HU-F02 (BT-5) + HU-F03 (BT-6) — Login + MFA, **bundle** |
| Sprint | 1 — Día 2 del ROADMAP (puede derramar a Día 3 por tamaño) |
| Spec | `specs/HU-F02-F03-login-mfa/SPEC.md` v1.0 |
| Plan | `specs/HU-F02-F03-login-mfa/plan.md` — **aprobado**, D1–D18 cerradas |
| Tasks | `specs/HU-F02-F03-login-mfa/tasks.md` — Lotes A–I (E DIFERIDO por D18) |
| Estado | **Lotes A–H cerrados**. HITO 5 (E2E manual `registro → login → MFA → dashboard`) verde el 2026-05-20. `mvn verify` verde con 90 tests. Sigue Lote I (tests frontend + APRENDIZAJES.md + PR). |

---

## Cómo continuar (handoff Claude → próximo agente)

**Estado del bundle — todos los Lotes funcionales cerrados y commiteados.**

| Lote | Commit | Resumen | HITO |
|---|---|---|---|
| A — JWT + filter + handlers | `c3fe843` (Codex) | `JwtService` HS256 + jjwt 0.12.x, `JwtAuthenticationFilter` validando Bearer, 2 excepciones + 2 handlers de token, `application-test.yml` con `jwt.secret`. | HITO 1 (compile verde) |
| B — Notification refactor | `1734a79` (Codex) | `WelcomeEmailDispatcher` → `MailNotifier` con `sendOtpEmail` + `sendAccountLockedEmail`, templates `otp.html` y `account-locked.html`, `AuditEventType` con eventos `*_EMAIL_FAILED`. | `MailNotifierTest` 6 verdes |
| C — Login flow | `a55c553` (Claude) | `UserRepository.findByEmailIgnoreCase`, `LoginAttemptTracker`, `TempSessionManager/TempSessionData/OtpGenerator`, DTOs + 3 excepciones + handlers (401/423/403), `LoginService` + `LoginController`, SecurityConfig permitAll. AuditEventType + `LOGIN_ATTEMPT`/`ACCOUNT_LOCKED`. | HITO 2 (mvn compile verde, 57 src) |
| D — MFA flow | *commit user* | `MfaAttemptTracker`, `TokenIssuer`, 4 DTOs + 6 excepciones + handlers, `MfaService` (verify + resend) + `MfaController`, SecurityConfig permitAll. AuditEventType + 4 eventos MFA. | HITO 3 (mvn compile verde, 71 src) |
| ~E — Refresh/Logout~ | DIFERIDO por D18 | Mini-HU futura `HU-F0X-token-rotation-logout`. | — |
| F — Tests backend + CI Redis | *commit user* | 7 tests unit (46 casos: JwtService, OtpGenerator, trackers, services, TokenIssuer), 3 IT (AuthFlowIT, LockoutFlowIT, OpenApiContractIT extendido), `ci.yml` con service redis. Fix de placeholder `${JWT_SECRET:}` + RegisterFlowIT sin exclude de Redis. | HITO 4 (`mvn verify` verde — 90 tests) |
| G — Frontend infra | *commit user* | `AuthContext` + `AuthProvider` + `useAuth`, `apiClient` con interceptors JWT (D18 simplificado), `ProtectedRoute`, tipos + mensajes ES extendidos, `useSession` borrado, `main.tsx` envuelto en AuthProvider, `errorParser` + `retryAfter`. | tsc/build/test frontend verdes |
| H — Frontend pages | *commit user* | 2 schemas zod, 3 hooks, 4 componentes (Countdown, OTPInput, AppHeader, ResendButton), `LoginForm`, 3 pages (LoginPage real, MFAVerifyPage, DashboardPage), `App.tsx` con rutas + ProtectedRoute. Fixes infra: `docker-compose.yml` con JWT_SECRET + `nginx.conf` sin trailing slash en proxy_pass. | **HITO 5 ✅** E2E manual completo registro → login → MFA con OTP de MailHog → dashboard. |

**Siguiente — Lote I (cierre del bundle):**

- ☐ **T8.1–T8.5** Tests frontend con Vitest + RTL: `AuthContext`, `LoginForm`, `MFAVerifyPage`, `ProtectedRoute`, `jwtInterceptor`.
- ☐ **T8.6** Verificación DoD spec §15 — marcar N/A explícitos los ítems de refresh/logout por D18.
- ☐ **T8.7** `APRENDIZAJES.md` — sección "Día 2-3 — HU-F02+F03" en primera persona, estilo Día 0/Día 1 ([[P4]]).
- ☐ **T8.8** PR `feat/HU-F02-F03-login-mfa` → `main` con plantilla CONVENTIONS §4.1 + checklist DoD (N/A explícitos por D18). HITO 6.

**Deuda nueva identificada durante el bundle (registrada en tasks.md):**
- Mini-HU `HU-F0X-token-rotation-logout`: `/refresh` con rotación, `/logout` con blacklist, cookie HttpOnly refresh, `useLogout`, single-flight refresh en jwtInterceptor. Diferida por D18.
- Limpiar `JWT_REFRESH_SECRET` de `.env.example` (no se usa — refresh token será opaco, no JWT, decisión D2).
- (Opcional) Refactor `JwtService` para inyectar `Clock` y poder testear expiración sin TTL negativo.

---

## Estilo de trabajo del usuario (preferencias validadas)

Estas reglas vienen de feedback explícito del usuario en sesiones previas; cualquier agente
debe respetarlas para no chocar con el estilo establecido.

| # | Preferencia | Cómo aplicarla |
|---|---|---|
| P1 | **Velocidad sobre cobertura.** SonarCloud a ~60% es aceptable por el plazo del MVP. | NO sugerir tests reflexivamente cuando el quality gate flagee. Documentar el gap como una decisión `Dxx` en `plan.md` de la HU. Tests críticos de seguridad/dinero NO son negociables. |
| P2 | **SPECs > bitácora de prompts.** El profesor confirmó que los SPECs son la evidencia académica principal; la bitácora `docs/prompts/sprint-X.md` **NO es entregable**. | No proponer crear/actualizar la bitácora. El esfuerzo va a calidad/completitud de specs (secciones faltantes, decisiones, changelog, trazabilidad). |
| P3 | **Cadencia SDD: lotes + hitos.** El usuario produce mejor cuando trabajamos en lotes lógicos con validación en hitos significativos (compila / mvn verify / E2E manual), no archivo-por-archivo. | No micro-checkpoint. Producí un lote entero, reportá lo hecho + cómo verificarlo, esperá feedback. |
| P4 | **`APRENDIZAJES.md` al cierre de cada HU/Día.** Es la bitácora personal del usuario (no del proyecto). | Al cerrar una HU, proponer una sección nueva en primera persona, estilo Día 0/Día 1 del archivo. Headers por tema, **bold** los insights clave + por qué. |
| P5 | **No commits autónomos.** El humano firma todos los commits. El agente prepara mensajes (archivos en `%TEMP%` o pegados al chat) listos para `git commit -F`. | Cada vez que produzcas código, dejá el mensaje del commit en un archivo limpio (sin sangría — el here-string de PS coló espacios en HU-F01, ojo). |
| P6 | **Co-author trailer obligatorio** en todo commit asistido por IA. CONVENTIONS §11.6. | `Co-authored-by: <nombre-agente> <noreply@anthropic.com>` (Claude) o el dominio del proveedor (Codex: `<noreply@openai.com>` o similar). El humano puede unificar el trailer si prefiere — preguntale. |
| P7 | **Inconsistencias entre docs maestros: PARÁ y preguntá.** | No "arreglar silenciosamente". El humano decide cuál vale; queda como decisión `Dxx`. Ver D1 (interface naming con/sin `I`) como ejemplo. |
| P8 | **Branch protection en GitHub está deshabilitada deliberadamente** por plazo (decisión registrada). | No sugerir reactivarla salvo pedido explícito. |

---

## Decisiones locked (NO override)

Cada HU acumula decisiones `Dxx` en su `plan.md`. Antes de codear, leelas. Las más
transversales (aplican incluso fuera de la HU original):

- **HU-F01 D1**: Interfaces inter-módulo **sin prefijo `I`**. `Notifier` (no `INotification`),
  `Auditor` (no `IAudit`), `BalanceInitializer` (no `IBalanceInitializer`).
- **HU-F01 D10/D14**: Códigos de error en SCREAMING_SNAKE como `message` de la constraint;
  el `GlobalExceptionHandler` los mapea a texto humano via `ValidationMessages`. Cuando
  hay **un solo fieldError**, su código sube al `error` top-level del `ErrorResponse`.
- **HU-F01 D13**: BCrypt cost **12** explícito (`new BCryptPasswordEncoder(12)`). El default
  de Spring es 10. Tests assertan `password_hash.startsWith("$2a$12$")`.
- **HU-F01 D16**: Perfil `test` usa **Postgres del docker-compose en `localhost:5433/bloomtrade_test`**,
  NO Testcontainers. Razón: incompatibilidad de `docker-java 1.19.x` con el pipe
  `dockerDesktopLinuxEngine` de Docker Desktop reciente en Windows. NO intentar pelearse
  con Testcontainers; aceptar el pivot.
- **HU-F01 D17**: Coverage 60% aceptado por plazo.
- **HU-F02 D18**: `/refresh` y `/logout` **DIFERIDOS** a mini-HU post-MVP. NO los implementes
  en este bundle aunque la SPEC los mencione. Lo que se construye: `/login`, `/mfa/verify`,
  `/mfa/resend`, `JwtAuthenticationFilter`, AuthContext frontend, ProtectedRoute,
  interceptor 401→/login (sin refresh transparente).

---

## Reglas duras (de CLAUDE.md, resumidas)

- **Una HU = una rama** (`feat/HU-FXX-...`).
- **Conventional Commits** + `refs HU-FXX` + spec path en el footer.
- **Co-author trailer** en todo commit asistido por IA (P6).
- **Squash and merge** al pasar a `main`.
- **BigDecimal** para todo monto. NUMERIC(19,N) en BD. Nunca `double`/`float`.
- **Constructor injection**, nunca `@Autowired` en fields.
- **BCrypt** para passwords. Nunca SHA1/MD5/plaintext.
- **Migraciones Flyway** mergeadas son inmutables. Para cambios: V(n+1).
- **No catch genérico** de `Exception` salvo en `GlobalExceptionHandler`.
- **No `@Data` ni `@AllArgsConstructor`** en entidades JPA (rompe equals/hashCode).
- **No agregar libs** sin actualizar STACK.md en el mismo PR.
- **No exponer entidades JPA** en controllers — siempre DTO.
- **No tocar código de migraciones de HU previas** sin discusión.

---

## Setup del entorno (Windows del usuario)

- **JDK 21** (Temurin) instalado y en PATH.
- **Maven Wrapper** en `backend/mvnw[.cmd]` — usar siempre el wrapper, no `mvn` global.
- **Node 20.x + npm 10.x** instalados.
- **Docker Desktop** corriendo. Compose v2 (`docker compose`).
- **Postgres del compose** en `localhost:5433/bloomtrade` y `bloomtrade_test`.
- **JWT_SECRET** en `.env` debe ser ≥32 bytes. `JwtService` falla al arrancar si no.

PowerShell 5.1 es el shell primario. Algunas mañas:
- No usar `&&` ni `||`. Encadenar con `;` o `if ($?) { ... }`.
- Para multi-line: here-strings `@'...'@` con el cierre en columna 0.
- `curl` en PS es alias de `Invoke-WebRequest`; para curl real usar `curl.exe`.

---

## Memoria local del agente (Claude-específica, no portable)

Las preferencias P1–P8 de arriba están consolidadas de la memoria local de Claude en
`C:\Users\juang\.claude\projects\K--Repos-BloomTrade\memory\`. Esa memoria **no es
portable** — Codex/Cursor/etc. no la ven. Por eso este `AGENTS.md` es la fuente
autoritativa de las preferencias cross-agente. Si descubrís una preferencia nueva del
usuario durante una sesión, **actualizá esta sección** (no solo tu memoria local).

---

## Cuando el agente cambia (Claude ↔ Codex ↔ Cursor)

1. **El agente saliente** actualiza la sección "Trabajo activo" + "Cómo continuar"
   con el estado exacto. Confirma con el humano que git refleja el estado correcto.
2. **El agente entrante** lee este `AGENTS.md` primero, después `CLAUDE.md`, después
   los maestros, después spec/plan/tasks de la HU. Confirma con el humano qué tarea
   sigue antes de codear.
3. **El humano no debe** tener que re-explicar decisiones tomadas — todas están en
   `plan.md` de la HU correspondiente y en este archivo. Si el agente entrante
   pregunta por una decisión ya tomada, redirigilo al doc.
