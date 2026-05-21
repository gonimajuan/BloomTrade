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
| Branch | `feat/HU-F04-F20-perfil-notificaciones` |
| HU | HU-F04 (BT-7) + HU-F20 (BT-9) — Perfil + Canal de notificación, **bundle** |
| Sprint | 1 — Día 3 del ROADMAP |
| Spec | `specs/HU-F04-F20-perfil-notificaciones/SPEC.md` v1.1 (publicada 2026-05-20 con changelog alineando D1+D18) |
| Plan | `specs/HU-F04-F20-perfil-notificaciones/plan.md` — D1–D22 (D19–D22 son ajustes descubiertos en runtime, ver Lotes A/C/D/F) |
| Tasks | `specs/HU-F04-F20-perfil-notificaciones/tasks.md` — Lotes A–G |
| Estado | **Lotes A–F cerrados** (2026-05-20). HITOS 1, 2, 3, 4, 5 verdes. `mvn verify` verde con +28 tests nuevos (~118 total). Lote G en cierre: APRENDIZAJES.md actualizado; pendiente E2E visual humano + commit. |

---

## Cómo continuar (handoff Claude → próximo agente)

**Estado del bundle HU-F04+F20 — Lotes A–F funcionales cerrados; Lote G en cierre antes del commit grande.**

| Lote | Resumen | HITO |
|---|---|---|
| A — Migración V3 + entidad | `V3__user_profile_extension.sql` con `notification_channel` + `tickers_of_interest JSONB` + 2 check constraints + índice GIN. Enums `NotificationChannel`, `Market`. `User` extendido + método de dominio `applyProfileUpdate(...)` (D19, encapsulación PATCH). | HITO 1 ✅ (mvn compile + Flyway aplicó V3 verde, Spring Boot arrancó 8.3s) |
| B — Catálogo + validadores + audit enum | `AllowedTickers` (25 tickers agrupados por mercado), `@AllowedTicker` + `AllowedTickerValidator`, `@NoDuplicates` + `NoDuplicatesValidator`. `AuditEventType` + `PROFILE_UPDATED`, `NOTIFICATION_CHANNEL_CHANGED`, `PROFILE_UPDATE_FAILED`. `validation-messages.properties` + 5 códigos. | Lote B verde (incluido en HITO 4) |
| C — DTO + Mapper + Service + Controller | `UpdateProfileRequest`, `UserProfileResponse`, `UserProfileMapper` (MapStruct), 4 excepciones, `GlobalExceptionHandler` con handler para `UnrecognizedPropertyException` (D3 strict Jackson) + handler para `InvalidFormatException` enum → `VALIDATION_INVALID_CHANNEL`. `ProfileService` con `Snapshot` interno para cambio-detection (D7, D17 idempotencia, D18 no-PII). `MeController` con `@AuthenticationPrincipal AuthenticatedUser` (D9 anti path-tampering). `fail-on-unknown-properties=true` global en `application.yml`. | HITO 3 ✅ (curl E2E: register → login → MFA → GET/PATCH /me + read-only rechazado + ticker inválido rechazado + idempotente) |
| D — Tests backend | `AllowedTickerValidatorTest` (5), `NoDuplicatesValidatorTest` (5), `UserProfileMapperTest` (3 — incluye assertion no-leak `passwordHash`/`$2a$`), `ProfileServiceTest` (8 — happy, multi-field, idempotente, no-PII, error-rethrow), `MeFlowIT` (7 — Postgres+Redis reales, registro→login→MFA→GET/PATCH /me). +28 tests. | HITO 4 ✅ (`mvn verify` BUILD SUCCESS — total ~118 tests) |
| E — Frontend infra | `constants/tickers.ts` (mirror del backend, sincronizar manualmente), `types/api.ts` + 4 types perfil, `schemas/updateProfile.ts` zod, `api/profileApi.ts`, `useProfile` + `useUpdateProfile` (cache invalidation + optimistic `AuthContext.updateUser` en cambio de nombre), `useDiscardChangesPrompt` (modal manual + `beforeunload`, D22), `DiscardChangesModal` componente genérico. `AuthContext` extendido con `updateUser(partial)`. `messages.es.ts` +5 códigos. | tsc verde |
| F — Pages + routing | `ProfilePage.tsx` (orquesta secciones: info personal, canal radio group, grid de tickers agrupado por mercado con contador, SaveCancelBar, modal de descarte). `App.tsx` + ruta `/profile` con `ProtectedRoute`. `AppHeader` + link "Mi perfil" (era placeholder en HU-F02-H; ahora navega). | HITO 5 ✅ (`npm run build` verde 180 modules, frontend container rebuildeado + healthy; `/profile` responde 200 — E2E visual queda para el humano) |
| G — Cierre | APRENDIZAJES.md sección "Día 3 — HU-F04+F20" agregada con 8 reflexiones técnicas. AGENTS.md "Trabajo activo" actualizado (este bloque). Tests frontend deferred [[P1]] (validadores + mapper backend cubren riesgos críticos). | Pendiente HITO 6 (commit + PR) |

**Pendiente al humano antes de mergear:**

- ☐ Validación visual E2E manual del flujo en browser (`localhost:5173/profile`): editar nombre + canal + 3 tickers → Guardar → recargar → cambios persistidos; editar y Cancelar → modal de descarte.
- ☐ Commit grande con los archivos del bundle (Lotes A–G). Mensaje sugerido en `C:\Users\juang\AppData\Local\Temp\bt-hu-f04-f20.txt` (Claude lo deja preparado al cierre de esta sesión).
- ☐ PR `feat/HU-F04-F20-perfil-notificaciones` → `main` con plantilla CONVENTIONS §4.1.

**Decisiones nuevas registradas (Dxx — además de las del plan.md original):**

- **D19** (Lote A): el PATCH parcial se encapsula en `User.applyProfileUpdate(...)` (método de dominio), no con setters Lombok. `getTickersOfInterest()` retorna vista inmutable.
- **D20** (Lote C smoke): `GET /me` sin token devuelve 403 (no 401 como pedía SPEC §5.3.1) porque Spring Security sin `AuthenticationEntryPoint` custom mapea `anyRequest().authenticated()` a 403. Deuda pre-existente desde HU-F02, no regresión.
- **D21** (Lote D test fix): el catch de `DataAccessException` en `ProfileService.updateMe()` emite 2 audits (PROFILE_UPDATED ya emitido antes del mapper.toResponse + PROFILE_UPDATE_FAILED en catch). Audit post-commit transaccional es over-engineering MVP.
- **D22** (Lote F): `useBlocker` requiere `createBrowserRouter` (DataRouter). `main.tsx` usa `BrowserRouter` clásico. En vez de migrar router (riesgo de regresión), `useDiscardChangesPrompt` es manual: Cancel button abre modal + `beforeunload` cubre cierre. Pierde modal en navegación SPA via link — riesgo bajo MVP.

**Deuda nueva identificada (post-bundle):**

- Tests frontend del Lote G saltados — `useProfile.test`, `useUpdateProfile.test`, `ProfilePage.test`, `DiscardChangesModal flow`. Cobertura crítica (no-leak `passwordHash`, validators, audit dispatch) está en backend.
- `ARCHITECTURE.md` §5 aún lista interfaces con prefijo `I` (deuda doc-only declarada en SPEC v1.1).
- Migración a DataRouter del frontend → habilita `useBlocker` para confirmación de descarte en navegación SPA.
- Generación automática de `constants/tickers.ts` desde el OpenAPI enum del backend (hoy se mantienen sincronizados manualmente).

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
