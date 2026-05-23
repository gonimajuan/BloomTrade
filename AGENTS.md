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
| Branch | `main` — limpia y sincronizada con `origin/main`. Sin ramas locales/remotas pendientes (`feat/HU-F01-registrarse` remota borrada 2026-05-22, `feat/HU-F09-orden-compra-market` local borrada post-merge). |
| HU activa | **Ninguna**. HU-F09 mergeada en `main` vía PR #6 (commit `1bab23b`, 2026-05-22 noche). Próximo: HU-F10 Venta Market (Día 7). |
| Sprint | 2 en curso. Días 5 (HU-F11 Encolar/Mensajería) saltado deliberadamente; Día 6 (HU-F09) completo. Próximo: Día 7 (HU-F10), Día 8 (HU-F16 portfolio + HU-F21 saldo), Día 9 (HU-F18 dashboard), Día 10 (Sprint 1+2 Review/Retro diferidos). |
| Próximo paso | Sesión nueva: arrancar HU-F10 vía SDD (SPEC → plan → tasks → lotes). Ver sección "Cómo continuar (handoff post HU-F09 → arranque HU-F10)" abajo. |
| Deuda viva (NO bloqueante) | (1) Mini-HU `HU-F0X-token-rotation-logout`. (2) Tests IT webhooks Stripe con WireMock. (3) `ARCHITECTURE.md` §5 interfaces con prefijo `I`. (4) `useBlocker` requires DataRouter migration. (5) Generación auto de `frontend/constants/tickers.ts` desde OpenAPI. (6) `JWT_REFRESH_SECRET` eliminado de `.env.example`. (7) Sprint 1 Review+Retro diferidos a Día 10. (8) **Reconciliation Alpaca-paper vs BloomTrade BD** (D17 + D29 HU-F09) — extiende a órdenes encoladas `PENDING + alpacaOrderId != null`: job nocturno o webhook handler para actualizar al fill. (9) `clientOrderLocks` ConcurrentHashMap crece monotónico (D25) — MVP single-user insignificante. (10) Polygon.io como alterno de market data (post-MVP). (11) **D28 hardening**: agregar check en `IntegrationConfig.validateCredentials` que rechace `ALPACA_BASE_URL` terminado en `/v2` con mensaje claro. (12) **D29 hardening**: el toast de orden encolada en frontend solo se muestra una vez; tras refresh la orden PENDING no se ve en ninguna parte (HU-F16 portfolio mostrará "órdenes en cola" cuando entre Día 8). (13) **HU-F09 orden encolada del demo del 2026-05-22**: una orden `PENDING + alpacaOrderId` quedó en BD del demo viernes fuera de horario. Si NYSE/Alpaca la fileó en la apertura del martes, hay drift entre Alpaca y BD — opciones: aceptar y dejar como "está en cola para siempre en BD" (MVP), o hacer reset manual del paper account + truncar `app.orders` antes de la próxima demo. |

---

## Cómo continuar (handoff post HU-F09 → arranque HU-F10)

**Estado actual (2026-05-22 noche, cierre de Día 6):**
- 5 bundles mergeados en `main`: HU-F01 (PR #2), HU-F02+F03 (PR #3), HU-F04+F20 (PR #4), HU-F06 (PR #5), **HU-F09 (PR #6, merge commit `1bab23b`)**.
- Working tree limpio. Sin ramas pendientes. `git status` reporta nothing to commit.
- `mvn verify` última corrida verde: 219 tests (188 unit + 31 IT).
- `npm run build` última corrida verde: 195 módulos.
- Stack Docker funcional: postgres + redis + elasticsearch + logstash + kibana + mailhog + backend + frontend (todos healthy en última sesión).
- Backend E2E: quote + placeOrder con idempotencia, concurrencia real, Alpaca trading + data API integrados, manejo de mercado cerrado vía `ORDER_QUEUED` (D29). Validado en HITO 8 humano.
- Frontend E2E: `/trade` con OrderForm + OrderQuotePanel + OrderConfirmationToast (palette emerald para EXECUTED + ámbar para PENDING/QUEUED).

**Lo primero en la sesión de HU-F10 (Día 7):**

1. **Leer este `AGENTS.md` completo + `CLAUDE.md` completo**.
2. **Leer los maestros**: `ARCHITECTURE.md` (módulos no cambiaron), `STACK.md` (Alpaca confirmado §7.1+§7.2), `CONVENTIONS.md`, `ROADMAP.md` Día 7.
3. **Leer la HU previa como referencia obligatoria**: `specs/HU-F09-orden-compra-market/SPEC.md` (v1.1) + `plan.md` (D1–D29). HU-F10 **reutiliza casi todo el andamio**: `app.orders`, `app.positions`, `AlpacaTradingAdapter`, `MarketDataAdapter`, `CommissionManager`, `OrderEventListener`, `TradingService` (extender con método `placeOrderSell` o parametrizar el actual con `OrderSide`), los 4 componentes frontend (habilitar toggle SELL en `OrderForm`).
4. **Crear `specs/HU-F10-orden-venta-market/`** vía SDD Paso 1 (spec) → Paso 2 (plan) → Paso 3 (tasks). Esperá aprobación del humano entre pasos (CLAUDE.md Paso 2: "Espera mi aprobación del plan antes de escribir código").
5. **Estimar complejidad**: ~40-50% de HU-F09 porque el andamio existe. Cambios principales esperables:
   - **Validar `Position.quantity >= sellQuantity`** antes del INSERT order (analogía a `PortfolioService.debit` pero sobre posición).
   - **Decrementar position** en lugar de upsert. Si `quantity` queda en 0, eliminar la fila (o setear status soft-delete).
   - **Credit en `PortfolioService`** (no debit) — agregar método `credit(userId, amount)` con `noRollbackFor` consistente con D24.
   - **Nuevas validaciones**: `SHORT_SELLING_NOT_ALLOWED` si el usuario no tiene la posición. `INSUFFICIENT_SHARES` si pide vender más de lo que tiene.
   - **AlpacaTradingAdapter.submitMarketOrder** ya soporta `side="sell"` (ver `AlpacaOrderRequest.market` línea 27) — confirmar paso de `OrderSide.SELL` desde TradingService.
   - **Email nuevo**: `order-executed-sell.html` (similar a buy pero con crédito al balance + decremento de posición). Reutilizar plantilla con condicional Thymeleaf o crear separadas — decisión D30 emergente.
   - **Frontend**: habilitar el botón SELL en `OrderForm.tsx` (quitar `disabled`). Quizás un dropdown que solo muestre tickers que el usuario tiene en posición (consulta nueva `GET /portfolio/positions` — pero esa es HU-F16 Día 8). Para MVP, dejar el dropdown completo y validar server-side.
6. **HITO equivalente al 8 de HU-F09**: demo manual con AAPL primero comprado (puede ser una orden nueva del martes con mercado abierto) y luego vendido. Toast emerald "Orden de venta ejecutada — recibirás USD X" + email + audit `ORDER_EXECUTED` con side=SELL + position decrementada + balance creditado.
7. **Validación opcional pre-arranque**: cuando el mercado abra (martes 26 May 2026 8:30 AM hora Colombia), correr una compra real para validar el happy path `EXECUTED` de HU-F09 que el viernes no se pudo (mercado cerrado). Si pasa, también valida que la deuda #13 sigue abierta o cerrada (¿la orden encolada del viernes finalmente fileó?).

**Pre-requisitos para arrancar HU-F10:**

- `.env` ya tiene creds Alpaca pobladas (HU-F09).
- Docker stack puede levantarse con `docker compose up -d` (sin `--build` si no hay cambios en Dockerfile).
- `ALPACA_BASE_URL=https://paper-api.alpaca.markets` (sin `/v2` — D28).
- Reconciliación de Alpaca paper account: en https://app.alpaca.markets/paper/dashboard/overview verificar que la orden encolada del viernes filee o no. Si no, no afecta a HU-F10 (cada orden es independiente por `clientOrderId`).

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

**Lo primero en la próxima sesión** (post-merge de HU-F09 → Día 7 HU-F10):
1. Leer `CLAUDE.md` + este `AGENTS.md` completos.
2. Confirmar que PR de HU-F09 está mergeado en `main` y la branch local `feat/HU-F09-orden-compra-market` ya no es la activa.
3. `git checkout main; git pull; git checkout -b feat/HU-F10-orden-venta-market`.
4. Leer `specs/HU-F09-orden-compra-market/SPEC.md` (v1.1) como referencia — HU-F10 reutiliza el andamio: `app.orders`, `app.positions`, `AlpacaTradingAdapter`, `CommissionManager`, `OrderEventListener`, los 4 componentes frontend (toggle BUY/SELL se habilita).
5. Crear `specs/HU-F10-orden-venta-market/` con SPEC + plan + tasks. La complejidad esperada es ~40% de HU-F09 porque la infraestructura está hecha. Cambios principales: validar que `Position.quantity >= sellQuantity`, decrementar position en lugar de upsert, debit comisión + (si SELL, credit subtotal-comisión), nueva validación SHORT_SELLING_NOT_ALLOWED.
6. HITO equivalente al 8 de HU-F09: demo manual con AAPL primero comprado (puede ser orden ya encolada del demo HU-F09) y luego vendido.

**Pendiente del humano antes de empezar HU-F10:**

1. **Firmar commit + push + PR de HU-F09**:
   - `git add -A` (52 archivos: backend + frontend + docs + migration V5 + templates).
   - `git commit -F C:\Users\juang\AppData\Local\Temp\bt-hu-f09.txt` (P6: ruta completa, no `$env:TEMP`).
   - `git push -u origin feat/HU-F09-orden-compra-market`.
   - `gh pr create --title "feat(trading): HU-F09 — compra Market con Alpaca paper trading" --body "..."` (o desde GitHub UI).
   - Squash and merge a `main`.

2. **Validación opcional post-merge mercado abierto**:
   - Cuando el mercado NYSE abra (lunes 25 May es Memorial Day USA → cerrado; primera ventana es **martes 26 May 2026, 8:30 AM hora Colombia = 9:30 AM ET**), repetir compra AAPL × 1 en `/trade`. Esperado: toast emerald ✅ "Orden ejecutada: 1 AAPL a USD …" + position creada + balance debitado por executionTotal real.
   - Si la orden encolada del viernes (status `PENDING + alpacaOrderId`) finalmente filea, la reconciliation manual queda pendiente (deuda #8 + #12 del AGENTS.md). Para MVP es OK que esa orden quede `PENDING` indefinidamente — el demo del happy path en HITO 8 se valida con la nueva.

**Pre-requisitos para arrancar Lote H:**
- `.env` con creds Alpaca pobladas (humano ya las tiene; verificar log de backend al arrancar: debería decir `Alpaca integration inicializada — trading: ... | data: ... | key: PK4Q****DSRC`).
- Docker stack arriba (`postgres:5433`, `redis:6379`, `elasticsearch:9200`, `logstash`, `mailhog`, `backend:8080`).
- Frontend dir: `K:\Repos\BloomTrade\frontend`. Verificar `npm install` está al día (HU-F06 no agregó deps; tampoco F09).

**Estructura de archivos frontend a crear (tasks.md Lote H):**

```
frontend/src/
├── pages/TradePage.tsx                                     ← T8.9
├── features/trading/
│   ├── components/{OrderForm,TickerDropdown,
│   │               OrderQuotePanel,OrderConfirmationToast}.tsx  ← T8.5-T8.8
│   ├── hooks/{useQuote,useSubmitOrder}.ts                  ← T8.3-T8.4
│   └── api/tradingApi.ts                                   ← T8.2

frontend/src/ modificados:
├── types/api.ts                ← +7 tipos T8.1
├── App.tsx                     ← +ruta T8.10
├── components/AppHeader.tsx    ← +link T8.11
└── i18n/messages.es.ts         ← +10 códigos T8.12
```

**Bugs encontrados y arreglados durante Lote G** (registrados como D23–D27 en plan.md §2.4):
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
