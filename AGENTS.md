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
| Branch | `feat/HU-F10-orden-venta-market` — local con cambios listos para commit + push + PR. Working tree con: 3 docs SDD untracked (`specs/HU-F10-orden-venta-market/`), `AGENTS.md` modificado (este handoff), backend ~15 archivos modificados + 7 nuevos, frontend 5 archivos modificados, `APRENDIZAJES.md` con sección Día 7. |
| HU activa | **HU-F10 Venta Market — IMPLEMENTACIÓN CERRADA (HITO 6 listo para commit)**. SDD Pasos 1–5 completos (sesiones 2026-05-23 docs + 2026-05-24 implementación). `specs/HU-F10-orden-venta-market/SPEC.md` v1.0 + `plan.md` v1.1 (con §2.4 D17–D21 emergentes) + `tasks.md`. **HITOs 1–4 verdes**, HITO 5 frontend `npm run build` verde + smoke E2E manual pendiente del humano (NYSE abierto). HITO 6 pendiente del commit + push + PR firmado por humano. |
| Sprint | 2 en curso. Día 7 (HU-F10) CERRADO 2026-05-24. Próximo: Día 8 (HU-F16 portfolio + HU-F21 saldo — SDD Paso 1 pendiente), Día 9 (HU-F18 dashboard — SDD pendiente), Día 10 (Sprint 1+2 Review/Retro diferidos). Día 5 (HU-F11 Encolar/Mensajería) saltado deliberadamente. |
| Próximo paso | **El humano firma el commit HU-F10** (mensaje en `C:\Users\juang\AppData\Local\Temp\bt-hu-f10.txt`, ruta completa P6) + push + PR. **Smoke E2E manual** (HITO 5 humano) idealmente con NYSE abierto — martes 26-May 2026 8:30 AM hora COL en adelante post-Memorial Day. Tras merge: arrancar SDD Paso 1 de HU-F16+F21 bundle (portafolio + saldo Día 8) reusando completo el `PortfolioService` de F09+F10. Ver sección "Cómo continuar (post HU-F10 → HU-F16+F21 Día 8)" abajo. |
| Deuda viva (NO bloqueante) | (1) Mini-HU `HU-F0X-token-rotation-logout`. (2) Tests IT webhooks Stripe con WireMock. (3) `ARCHITECTURE.md` §5 interfaces con prefijo `I`. (4) `useBlocker` requires DataRouter migration. (5) Generación auto de `frontend/constants/tickers.ts` desde OpenAPI. (6) `JWT_REFRESH_SECRET` eliminado de `.env.example`. (7) Sprint 1 Review+Retro diferidos a Día 10. (8) **Reconciliation Alpaca-paper vs BloomTrade BD** — extendido HU-F10 D9: además de BUY queued (cash debitado sin fill), ahora SELL queued (posición decrementada sin crédito acreditado). Riesgo asimétrico: si Alpaca cancela un SELL encolado, usuario pierde posición sin recibir crédito. Job nocturno o webhook handler post-MVP. (9) `clientOrderLocks` ConcurrentHashMap crece monotónico (D25 F09) — MVP single-user insignificante. (10) Polygon.io como alterno de market data (post-MVP). (11) **D28 hardening**: check en `IntegrationConfig.validateCredentials` que rechace `ALPACA_BASE_URL` terminado en `/v2` con mensaje claro. (12) **D29 hardening**: HU-F16 portfolio mostrará "órdenes en cola" cuando entre Día 8 — incluir tanto BUY como SELL queued. (13) HU-F09 orden encolada del demo viernes 2026-05-22: aún pendiente reconciliar manualmente vs Alpaca paper account. (14) **HU-F10 D17 hardening (post-MVP)**: el lock canónico `balances→positions` serializa dos SELLs concurrentes del mismo user sobre tickers DISTINTOS (no necesario funcionalmente). Si volumen multi-user lo justifica, refactorizar a per-ticker locks o lock-free atomic UPDATE. (15) **HU-F10 D10 (post-F16)**: cuando HU-F16 mergee, agregar feature flag o extender `useTickerOptions` para filtrar el `TickerDropdown` por posiciones del usuario cuando side=SELL. (16) **`InvalidSideException.sideNotYetImplemented()` dead code** post HU-F10: el método quedó como factory invocable pero nunca se llama; borrar en Día 8+ si confirmamos que ningún test F09 lo invoca. |

---

## Cómo continuar (post HU-F10 → HU-F16+F21 Día 8)

**Estado actual (2026-05-24, cierre de sesión implementación HU-F10):**

- HU-F10 implementación completa en branch `feat/HU-F10-orden-venta-market`. **250 tests verdes (207 unit + 43 IT)**, 0 regresiones. Frontend `npm run build` verde.
- HITOs 1–4 ✅ (backend + tests IT). HITO 5 ⏸️ smoke E2E humano (requiere mercado abierto). HITO 6 ⏸️ commit + push + PR firmado por humano.
- Decisiones emergentes registradas en `plan.md` §2.4: D17 (lock canónico balances→positions), D18 (`noRollbackFor` en `validateSellable` + `decrementPosition`), D19 (skip email SELL provisional Lote B→C), D20 (side en TODOS los audit events), D21 (dispatch en listener no en Notifier).
- SPEC sin bump v1.1 — todas las decisiones emergentes son implementation-detail, no afectan contratos API.
- APRENDIZAJES.md con sección Día 7 (8 reflexiones técnicas + meta sobre SDD).

**Lotes HU-F10 cerrados (A–F):**

| Lote | HITO | Resumen | Tests añadidos |
|---|---|---|---|
| A | 1 ✅ | Verificación V5 (D13 confirmó BD apta sin V6). `PositionRepository` + lock pessimistic + `deleteByUserIdAndTicker`. `Position.decrementBy + isDepleted`. `UserBalance.applyCredit`. `PortfolioService.credit + decrementPosition + findPosition`. 2 excepciones nuevas en `trading/exception/` (`ShortSellingNotAllowedException`, `InsufficientSharesException`). | 12 unit (credit happy/precision/edge + decrement residual/delete/insufficient/short/qty0-defensivo + findPosition × 2) |
| B | 2 ✅ | `Order.markAsExecuted` side-aware (BUY suma, SELL resta — D4 honra quotedCommission, no recalcula). `QuoteResponse` + `sufficientShares` + `userShares` con @Schema. `OrderResponse` @Schema side-aware. 4 records de evento + `side` (D20 emergente). `PortfolioService.validateSellable`. `TradingService` refactor con dispatch interno por side (`handleBuyTx` extraído + `handleSellTx` nuevo). `GlobalExceptionHandler` + 2 handlers 409. `validation-messages.properties` +2 códigos. | 8 unit nuevos (3 quote SELL + 5 placeOrder SELL) + 3 unit Order side-aware + 5 OrderEventListener actualizados |
| C | 3 ✅ | `Notifier` rename 4 métodos a `*Buy` + 4 nuevos `*Sell` (8 total). `MailNotifier` reescrito. 4 templates Thymeleaf `-sell.html` (templates `-buy.html` ya existían desde F09). 2 DTOs extendidos (`OrderExecutedEmailCommand` +positionResultingQty/Deleted, `OrderQueuedEmailCommand` +positionResultingQty). `OrderEventListener` dispatch real por `event.side()`. Skip email amplió a `SHORT_SELLING_NOT_ALLOWED` + `INSUFFICIENT_SHARES`. | 5 OrderEventListener nuevos SELL dispatch + 2 anti-spam shortSelling/insufficientShares |
| D | 4 ✅ | `TradingControllerSellIT` (9 tests E2E con WireMock — quote/placeOrder happy + total liquidation + short selling + insufficient shares + Alpaca rejected + Alpaca down + idempotency). `TradingServiceSellConcurrencyIT` (3 tests — idempotency × 10 + overlap position × 2 + BUY+SELL concurrente sin deadlock). **2 fixes emergentes durante el lote**: D17 lock canónico (deadlock atrapado), D18 noRollbackFor en validateSellable (500→409). | 12 IT nuevos |
| E | 5 ⏸️ | Frontend: `types/api.ts` + 2 campos QuoteResponse. `messages.es.ts` +2 códigos. `OrderForm` habilitado SELL toggle + submit label side-aware + hint dropdown. `OrderQuotePanel` branching side-aware (totalLabel, projectedBalance suma/resta, posición restante/liquidación, blockedReason 3 casos, confirmLabel). `OrderConfirmationToast` headline/mensaje side-aware (Vendiste/Compraste, queued SELL "recibirás crédito al abrir"). `npm run build` verde 195 módulos. Smoke E2E humano pendiente. | 0 (frontend skipped per [[feedback-coverage-vs-velocidad]] — validación humana HITO 5) |
| F | 6 ⏸️ | `plan.md` §2.4 con D17–D21 + changelog v1.1. `APRENDIZAJES.md` sección "Día 7 — HU-F10". `AGENTS.md` handoff actualizado (este bloque). Commit message en `C:\Users\juang\AppData\Local\Temp\bt-hu-f10.txt`. SPEC NO bump (decisiones implementation-detail). | — |

**Bugs encontrados y arreglados durante implementación HU-F10:**

1. **D17 — Deadlock BUY+SELL concurrente** atrapado por test `TradingServiceSellConcurrencyIT#concurrency_buyAndSellSameTicker_*`. Fix: `validateSellable` toma lock `app.user_balances` PRIMERO (orden canónico balances→positions consistente con BUY que toma balances en debit antes de positions en upsert). Sin esto, Postgres reportaba "deadlock detected" en `SELECT FOR UPDATE` de balances. Documentado javadoc PortfolioService.
2. **D18 — `UnexpectedRollbackException` en SHORT_SELLING/INSUFFICIENT_SHARES** atrapado por `TradingControllerSellIT` (7/9 pasaban, 2 daban 500). Fix: agregar `noRollbackFor={ShortSellingNotAllowedException, InsufficientSharesException}` a `validateSellable` y `decrementPosition`. Patrón idéntico a D24/D27 F09.

**Lo primero del humano (HITO 6 pre-merge):**

1. **Smoke E2E manual HITO 5** (idealmente con NYSE abierto):
   - `docker compose up -d --build` → login → /trade → toggle SELL.
   - **Pre-requisito**: tener al menos 1 posición real en `app.positions` (no `PENDING+alpacaOrderId`). Si la del demo F09 quedó encolada, ejecutar BUY × 5 AAPL primero con mercado abierto.
   - SELL AAPL × N (N ≤ posición) → quote panel muestra "Producto neto a recibir" + "Posición restante: K AAPL" + "Saldo después" incrementado → confirmar → toast emerald "Vendiste 5 AAPL — recibiste USD X".
   - Verificar email en MailHog `localhost:8025`: asunto "Tu orden de venta fue ejecutada" + cuerpo con producto neto + condicional "Posición restante" o "Has liquidado completamente".
   - Verificar Kibana `localhost:5601`: evento `ORDER_EXECUTED` con `details.side="SELL"`, `details.positionResultingQty=N`, `details.positionDeleted=true|false`.
   - `psql -c "SELECT * FROM app.orders WHERE side='SELL' ORDER BY submitted_at DESC LIMIT 1"` muestra `status=EXECUTED` + `execution_total ≈ subtotal − commission`.
   - `psql -c "SELECT * FROM app.positions WHERE user_id = '...'"` decrementada o borrada.
   - `psql -c "SELECT balance FROM app.user_balances WHERE user_id = '...'"` incrementado.
   - Alpaca paper dashboard (`https://app.alpaca.markets/paper/dashboard/overview`) muestra la venta.
   - **Smokes negativos**: SELL ticker sin posición → toast rojo "No tienes posición en {ticker}". SELL > posición → "Solo tienes {N} disponibles para vender".

2. **Commit + Push + PR**:
   - `git add -A`
   - `git commit -F C:\Users\juang\AppData\Local\Temp\bt-hu-f10.txt` (ruta completa literal — P6).
   - `git push -u origin feat/HU-F10-orden-venta-market`
   - `gh pr create` o desde GitHub. Squash and merge a `main`.

**Cómo arrancar HU-F16+F21 Día 8 tras merge HU-F10:**

1. Pre-requisito: HU-F10 mergeada en `main` (los `PortfolioService.getBalance/getPositions` ya existen del F09+F10 — F16+F21 son CRUDS pequeños encima).
2. SDD Paso 1: crear `specs/HU-F16-consultar-portafolio/SPEC.md` + `specs/HU-F21-consultar-saldo/SPEC.md`. **Decisión metodológica del usuario** (2026-05-23): NO batch-spec — cada HU su propio SPEC. Pero F16+F21 son tan small (~30% efforts F09 cada una) que podrían compartir un SPEC bundle `HU-F16-F21-portafolio-saldo/SPEC.md`. Discutir con usuario al arrancar Día 8.
3. Endpoints estimados: `GET /api/v1/portfolio/positions` (lista) + `GET /api/v1/portfolio/balance` (con currency). Frontend: nuevo tab `/portfolio` en AppHeader, hooks `usePortfolioPositions + useBalance`.
4. **Reuso máximo**: PortfolioService ya tiene `getBalance(userId)` y `getPositions(userId)` readOnly. Solo agregar 2 endpoints + 2 DTOs + 2 hooks frontend + 1 página. Sin migración nueva, sin Alpaca, sin event listeners.
5. Incluir órdenes `PENDING + alpacaOrderId` en `GET /positions` como sección separada "órdenes en cola" (D29 F09 + D9 F10) — mitigación de la deuda viva #8.

**Estado bundle anterior HU-F09 (cerrado y mergeado 2026-05-22 PR #6):**
- 5 bundles mergeados en `main`: HU-F01 (PR #2), HU-F02+F03 (PR #3), HU-F04+F20 (PR #4), HU-F06 (PR #5), **HU-F09 (PR #6, merge commit `1bab23b`)**.
- Working tree con 3 archivos **untracked** (NO commitear todavía — se commitean junto con la implementación de F10):
  - `specs/HU-F10-orden-venta-market/SPEC.md` v1.0 (~580 líneas, aprobado)
  - `specs/HU-F10-orden-venta-market/plan.md` v1.0 (~280 líneas, aprobado)
  - `specs/HU-F10-orden-venta-market/tasks.md` (~480 líneas, aprobado)
- Sin ramas activas. `git status` muestra los 3 untracked en `specs/`.
- `mvn verify` última corrida verde (sesión F09): 219 tests (188 unit + 31 IT).
- `npm run build` última corrida verde: 195 módulos.
- Stack Docker funcional (último uso F09 HITO 8): postgres + redis + elasticsearch + logstash + kibana + mailhog + backend + frontend.
- Decisión metodológica explícita del usuario (esta sesión): generar SDD docs solo de HU-F10 (no batch de las 3 HUs restantes) porque batching es mala práctica — el SPEC F16+F21 y F18 dependen de contratos reales que F10 va a establecer al implementarse. HU-F16+F21 y HU-F18 quedan con SDD Paso 1 pendiente, a hacer DESPUÉS de cerrar F10.

**Lo primero en la sesión de implementación HU-F10 (Día 7):**

1. **Leer este `AGENTS.md` completo + `CLAUDE.md` completo**.
2. **Leer los 3 docs SDD aprobados de F10 (en orden)**:
   - `specs/HU-F10-orden-venta-market/SPEC.md` — qué construir + criterios de aceptación.
   - `specs/HU-F10-orden-venta-market/plan.md` — 16 decisiones técnicas D1–D16, 6 lotes A–F, riesgos.
   - `specs/HU-F10-orden-venta-market/tasks.md` — descomposición granular T1.1–T6.11 (~80 tareas).
3. **Leer F09 como referencia obligatoria** (no para SDD nuevo, sino para entender el andamio que F10 extiende): `specs/HU-F09-orden-compra-market/SPEC.md` v1.1 + `plan.md` (D1–D29 emergentes).
4. **Crear branch + arrancar Lote A**:
   ```
   git checkout main
   git pull
   git checkout -b feat/HU-F10-orden-venta-market
   ```
   Después arrancar T1.1–T1.4 (verificación V5 — D13 del plan). Si V5 no cubre F10, crear V6 correctiva ANTES de continuar.
5. **NO crear SPEC/plan/tasks nuevos** — los 3 están listos y aprobados. Cualquier decisión emergente durante implementación va a `plan.md` §2.4 nueva sección "Decisiones emergentes durante implementación (D17–Dxx)" siguiendo patrón F09 D23–D29.
6. **Cadencia de lotes** [[feedback-cadencia-sdd]]: validar al cierre de cada HITO (1 a 6 del tasks.md), NO micro-checkpoint tras cada T1.x. Lotes A–D son backend; E es frontend; F es cierre (APRENDIZAJES + AGENTS.md handoff + commit message).
7. **HITO 5 demo manual** requiere NYSE abierto (martes 26-May 2026 8:30 AM hora COL en adelante, post-Memorial Day USA). Si la sesión arranca con mercado cerrado: completar HITOs 1–4 (backend + unit/IT) y posponer HITO 5 a la primera ventana abierta. Pre-HITO 5 setup: tener al menos 1 posición real en `app.positions` (no `PENDING+alpacaOrderId`) del usuario testing. Si la del demo F09 quedó encolada, ejecutar BUY × 5 AAPL primero.
8. **Cierre HITO 6**: commit + push + PR. Mensaje preparado en `C:\Users\juang\AppData\Local\Temp\bt-hu-f10.txt` (P6 ruta completa). El commit incluye los 3 docs SDD (untracked actualmente) + todo el código de implementación + APRENDIZAJES.md + handoff actualizado.

**Pre-requisitos para arrancar implementación HU-F10:**

- `.env` ya tiene creds Alpaca pobladas (HU-F09). NO se agregan vars nuevas.
- Docker stack puede levantarse con `docker compose up -d` (sin `--build` si no hay cambios en Dockerfile — pero F10 no toca Dockerfile).
- `ALPACA_BASE_URL=https://paper-api.alpaca.markets` (sin `/v2` — D28 F09).
- V5 ya migrada con `chk_order_side CHECK (side IN ('BUY', 'SELL'))` y `chk_position_quantity CHECK (quantity >= 0)` — T1.2/T1.3 lo verifican.
- Reconciliación de Alpaca paper account: en https://app.alpaca.markets/paper/dashboard/overview verificar estado. Si la orden encolada del viernes filee, hay posición real en Alpaca que BloomTrade aún tiene como PENDING — drift conocido (deuda #8/#13 de este AGENTS.md), no bloquea F10.

**Lotes HU-F09 cerrados (A–G):**

| Lote | HITO | Resumen | Tests añadidos |
|---|---|---|---|
| A | 1 ✅ | env vars + STACK.md §7.2 (Polygon→Alpaca) + V5 (orders/positions/commission_rates) + 6 entidades + 4 repositories + `application.yml` blocks `alpaca:` + `trading:` + retry instances | (compile + Flyway) |
| B | 2 ✅ | `IntegrationConfig` con 2 `RestClient` (trading + data) + `AlpacaTradingAdapter.submitMarketOrder/getOrder` + `MarketDataAdapter.getLatestPrice` + 3 excepciones + 3 DTOs + `SubmitMarketOrderCommand` | 12 unit (MockRestServiceServer) |
| C | 3 ✅ | `ConfigurationManager` + `CommissionManager` (BigDecimal HALF_UP scale=2) + `MarketScheduleManager` (stub MVP) | 17 unit (@CsvSource params) |
| D | 4 ✅ | `PortfolioService.debit/upsertPosition/getBalance/getPositions` + `UserBalance.applyDebit` + `InsufficientFundsException` + lock pessimistic | 8 IT (incl. concurrency 2-thread) |
| E | 5 ✅ | 4 DTOs API + `OrderMapper` (manual, BigDecimal→String) + 2 excepciones nuevas + 6 handlers en `GlobalExceptionHandler` + 7 códigos i18n + `TradingService.quote/placeOrderTx` + `OrderController` con Swagger | 12 unit |
| F | 6 ✅ | 3 EmailCommand DTOs + `Notifier` +3 métodos + `MailNotifier` +3 impl + 3 templates Thymeleaf + `AuditEventType` +10 entries + `OrderEventListener` con `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW, readOnly)` | 5 IT |
| G | 7 ✅ | `TradingControllerIT` (9 tests E2E con WireMock) + `TradingServiceConcurrencyIT` (idempotency × 10 + concurrency × 2) + 5 fixes críticos (D23–D27 emergentes en `plan.md` §2.4) | 11 IT |

**Bugs encontrados y arreglados durante Lote G HU-F09** (registrados como D23–D27 en plan.md §2.4 de F09):
1. Pre-validación fondos faltante antes de INSERT → agregada (D23).
2. `noRollbackFor` necesario en `placeOrderTx` + `PortfolioService.debit` para preservar filas FAILED/REJECTED (D24, D27 emergente del nested tx rollback-only).
3. Race condition find→INSERT con `clientOrderId` idempotency → `ConcurrentHashMap` lock + self-injection lazy para commit-DENTRO-de-lock (D25).
4. Hibernate L1 cache rompía `SELECT FOR UPDATE` → projection-only `findBalanceProjectionByUserId` (D26).
5. Retries override en `application-test.yml` (50ms × 2 vs 1s × 3) — solo en perfil test.

**Estado bundle anterior HU-F06 (cerrado y mergeado 2026-05-21):**

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
