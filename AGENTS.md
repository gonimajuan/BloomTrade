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
| Branch | `feat/HU-F06-suscripcion-premium` |
| HU | HU-F06 (BT-10) — Suscripción premium con Stripe |
| Sprint | 1 — Día 4 del ROADMAP |
| Spec | `specs/HU-F06-suscripción-premium/SPEC.md` **v1.2** (publicada 2026-05-21 con changelog Customer Portal + RAK + DPM tras consulta a skill `stripe-best-practices`) |
| Plan | `specs/HU-F06-suscripción-premium/plan.md` — D1–D21 (cubre RAK, DPM, Idempotency-Key, Customer Portal, split de transacciones, etc.) |
| Tasks | `specs/HU-F06-suscripción-premium/tasks.md` — Lotes A–H |
| Estado | **Lotes A–H cerrados** (2026-05-21). HITO 1 verde (compile + V4 DDL válido). HITO 4 verde (`mvn verify` con 124 tests, +14 nuevos). HITOS 2, 3, 5 PENDIENTES del humano: requieren setup Stripe (RAK + 2 Prices + Customer Portal config en Dashboard + `stripe-cli` para forwarding). Frontend build verde (187 modules). |

---

## Cómo continuar (handoff Claude → próximo agente)

**Estado bundle HU-F06 — código y tests verdes; HITOS visuales pendientes del setup manual de Stripe.**

| Lote | Resumen | HITO |
|---|---|---|
| A — Setup + entidades | `pom.xml` + `stripe-java 28.0.0`. `STACK.md` §2.3 actualizado. `.env.example` y `docker-compose.yml` con `STRIPE_API_KEY` (RAK), `STRIPE_WEBHOOK_SECRET`, `STRIPE_PRICE_MONTHLY/YEARLY`, `APP_BASE_URL`. V4 migration: `users.stripe_customer_id`, `app.subscriptions` (con `uq_one_active_subscription_per_user` partial unique), `app.stripe_webhook_events` (`stripe_event_id UNIQUE`). User entity + `linkStripeCustomer(...)` D8. Enums `SubscriptionStatus`, `BillingPlan`, `WebhookEventStatus`. Entidades `Subscription` (con métodos de dominio `scheduleCancellation/reactivate/markAsCancelled/markAsPastDue/syncPeriod`) y `StripeWebhookEvent`. 2 repositories. `StripeConfig` que valida key + WARN si no es `rk_`. SecurityConfig exime `/api/v1/webhooks/stripe`. | HITO 1 ✅ (compile + V4 DDL syntactically valid en BD) |
| B — StripeAdapter | `StripeAdapter` (5 métodos: `createCustomer`, `createCheckoutSession`, `createBillingPortalSession`, `retrieveSubscription`, `constructWebhookEvent`). `@Retry(name="stripeApi")` configurado en `application.yml` (3×backoff 1s/3s/9s). `Idempotency-Key` UUID por request en mutables (D4). **NO incluye `payment_method_types`** (D3 trap). `StripeApiException` con `stripeErrorCode`. | Compile verde |
| C — Service + Controller + isPremium en /me | 5 DTOs (CheckoutSessionRequest/Response, PortalSessionResponse, SubscriptionDto, SubscriptionStatusResponse). `SubscriptionMapper` MapStruct sin `stripeCustomerId`/`stripeSubscriptionId` (D21). 3 excepciones + 4 handlers en GlobalExceptionHandler. ValidationMessages +5 códigos. `SubscriptionService` con D8 split (`ensureStripeCustomer` REQUIRES_NEW). `SubscriptionController` 3 endpoints `@AuthenticationPrincipal`. **G5**: extendido `UserProfileResponse` con `boolean isPremium`; ajustada firma `UserProfileMapper.toResponse(User, boolean)`; `ProfileService` inyecta `SubscriptionService.isPremium`. Tests previos (HU-F04) ajustados. AuditEventType +3. | HITO 2 ⏸️ (curl /checkout-session requiere setup Stripe del usuario) |
| D — Webhook handler + idempotencia | `StripeWebhookHandler` con: signature verify (lanza `WebhookSignatureInvalidException`), idempotencia vía INSERT con catch de `DataIntegrityViolationException`, switch sobre 4 event types (checkout.completed, sub.updated con detect de transición cancel/reactivate, sub.deleted, invoice.payment_failed). Audit con 10 event types nuevos. `StripeWebhookController` con `@RequestBody String rawBody` para preservar bytes. | HITO 3 ⏸️ (requiere `stripe-cli trigger`) |
| E — Notification + 4 templates | `Notifier` interface +4 métodos. `MailNotifier` implementa con `@Async`. 4 templates Thymeleaf inline-CSS: `welcome-premium.html`, `subscription-scheduled-to-cancel.html`, `subscription-expired.html`, `subscription-payment-failed.html`. AuditEventType +4 `*_EMAIL_FAILED`. | Verificable en MailHog tras HITO 3 |
| F — Tests backend críticos | `SubscriptionMapperTest` (2 tests: mapeo OK, no-leak de stripe IDs en JSON). `SubscriptionServiceTest` (9 tests: happy, customer reuso, 409 already_active, 409 no_stripe_customer, Stripe API error con audit FAILED, portal happy, isPremium true/false, status null sub). `StripeWebhookHandlerTest` (3 tests: signature inválida, duplicate short-circuit, tipo desconocido ignorado). **Skip por velocidad**: IT con WireMock (deuda registrada), tests específicos de los 4 handlers individuales (cobertura E2E manual con stripe-cli). | HITO 4 ✅ (124 tests, BUILD SUCCESS) |
| G — Frontend | `types/api.ts` +6 types. `subscriptionApi.ts` 3 wrappers. 3 hooks (`useSubscription` con polling opcional, `useStartCheckout` redirige a Stripe, `useOpenBillingPortal` redirige a Portal). `messages.es.ts` +4 códigos. `PremiumPage` 4 estados (A: sin sub, B: ACTIVE sin cancel, C: ACTIVE con cancel, D: terminal). `PremiumSuccessPage` con polling y redirect a /dashboard en 3s. `PremiumCancelPage`. App.tsx +3 rutas. AppHeader + link "Premium". | HITO 5 ⏸️ (E2E con stripe-cli + Stripe Dashboard) |
| H — Cierre | APRENDIZAJES.md sección "Día 4 — HU-F06" con 9 reflexiones técnicas. AGENTS.md handoff (este bloque). Mensaje de commit preparado en `C:\Users\juang\AppData\Local\Temp\bt-hu-f06.txt`. | HITO 6 pendiente del commit + push + PR del humano |

**Pendiente del humano antes de mergear:**

1. **Setup Stripe (~10 min)**:
   - Crear Test Mode RAK en https://dashboard.stripe.com/test/apikeys con permisos: Customers (write), Checkout Sessions (write), Subscriptions (read), Billing Portal Sessions (write).
   - Crear 2 Products + Prices en https://dashboard.stripe.com/test/prices: USD $12/mo (`STRIPE_PRICE_MONTHLY`) y USD $120/yr (`STRIPE_PRICE_YEARLY`). Copiar los `price_...` IDs.
   - Activar Customer Portal: Settings → Billing → Customer Portal → habilitar cancel, payment method update, invoice history.
   - Instalar `stripe-cli`: `scoop install stripe-cli` en PowerShell.
   - `stripe login` (autenticación browser).
   - `stripe listen --forward-to localhost:8080/api/v1/webhooks/stripe` en otra terminal → copiar el `whsec_...` que sale al output.
   - Poblar `.env` con los 4 valores Stripe.

2. **HITOS visuales** (~15 min con setup arriba):
   - `docker compose up -d --build backend frontend` (rebuild para que tomen las env vars nuevas + V4 migrate).
   - Verificar logs backend: `Stripe SDK inicializado con key rk_test_****`. Si dice `sk_test_****` → WARN (cambiar a RAK).
   - Browser: login + MFA → menú "Premium" → activar mensual → 4242 4242 4242 4242 → /premium/success → ver banner premium → "Gestionar suscripción" → cancelar en portal → volver a /premium → banner amarillo.
   - Verificar email en MailHog (`localhost:8025`): "Bienvenido a Premium" + "Tu suscripción se cancelará el X".
   - Verificar `psql ... -c "SELECT * FROM app.subscriptions;"` y `SELECT * FROM app.stripe_webhook_events;`.

3. **Commit + PR**:
   - `git add -A`
   - `git commit -F C:\Users\juang\AppData\Local\Temp\bt-hu-f06.txt`
   - `git push -u origin feat/HU-F06-suscripcion-premium`
   - `gh pr create ...` o desde GitHub.

**Decisiones registradas (D1–D21 en plan.md):**

- D1: stripe-java 28.0.0, sin override de apiVersion (skill: "Always use the latest").
- D2: RAK (rk_) en lugar de sk_. `STRIPE_API_KEY` env var genérica.
- D3: NUNCA `payment_method_types` en checkout (DPM trap de la skill).
- D4: Idempotency-Key UUID por request en mutables.
- D5: Webhook raw body + signature verify, exento de JWT.
- D6: Idempotencia inbound vía UNIQUE constraint + catch DataIntegrityViolationException.
- D7: Customer Portal endpoint reemplaza /cancel (v1.2 SPEC).
- D8: Split de transacciones — `ensureStripeCustomer` en REQUIRES_NEW para evitar huérfanos.
- D9: Sub-paquete `auth/subscription/` (no módulo nuevo).
- D10: `invoice.payment_failed` → downgrade inmediato a PAST_DUE (MVP — sin grace period).
- D11–D13: Notifier extendido + 4 templates Thymeleaf.
- D14: AuditEventType +13 entries.
- D17: PremiumPage con 4 estados inline (no proliferación de componentes).
- D18: stripe-cli requerido en dev local.
- D19: tests IT con WireMock (deuda registrada — saltado por velocidad).
- D21: stripe_customer_id / stripe_subscription_id NUNCA en responses API. Verificado con test.
- **D22** (runtime fix HU-F06 Lote D): `EventDataObjectDeserializer.getObject()` retorna `Optional.empty()` cuando la API version del evento de Stripe difiere de la del SDK (caso real: evento `2026-04-22.dahlia` vs `stripe-java 28.0.0`). Solución: `extractObject` usa `deserializeUnsafe()` como fallback. Patrón estándar documentado en stripe-java README — riesgo aceptable porque solo accedemos a campos estables (subscription id, customer id, period start/end, metadata).
- **D23** (runtime fix cross-HU 2026-05-21): el access token + user se persisten en `localStorage` (`bloomtrade.accessToken`, `bloomtrade.user`) en lugar de vivir solo en memoria. Revierte parcialmente D12 HU-F02 §12.3 por UX inaceptable: cualquier F5 / vuelta de checkout Stripe / pestaña nueva forzaba re-login. Trade-off de seguridad XSS aceptado para MVP; mitigado por (a) TTL 15 min sin refresh silencioso, (b) `onUnauthorized` limpia ambas keys al primer 401. La mini-HU `HU-F0X-token-rotation-logout` reemplazará esto con cookie HttpOnly + refresh tokens y eliminará el localStorage.

**Deuda registrada (no bloqueante para MVP):**

- Tests IT con WireMock para los 4 webhook handlers individuales — actualmente solo signature + idempotency + tipo desconocido tienen tests.
- Test assertion explícita de no `payment_method_types` en code (D3) — saltado por velocidad.
- Reconciliation job nocturno entre Stripe y BD — para producción real.
- Tests frontend del Lote G — saltados (mismo motivo HU-F04).
- Migración del frontend a `createBrowserRouter` (DataRouter) → habilita `useBlocker` (deuda HU-F04 D22 — sigue válida).
- `ARCHITECTURE.md` §5 todavía lista interfaces con prefijo `I` (deuda doc-only HU-F02).

**Historial de bundles previos cerrados** (HU-F04+F20 — Día 3): SPEC v1.1 + 7 lotes + HITOs 1-5, 28 tests nuevos. Decisiones D19-D22 (encapsulación PATCH, 403 vs 401 deuda, audit post-commit, `useBlocker` deferido). Mergeado en PR #4 (commit `0caeed7`).

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
