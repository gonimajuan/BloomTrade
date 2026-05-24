# plan.md — HU-F10 Orden de venta Market

> Plan técnico derivado de `specs/HU-F10-orden-venta-market/SPEC.md` v1.0.
> Estado: **pendiente de aprobación humana** (SDD Paso 2).
> Modificaciones a `SPEC.md` se difieren a HITO 6 (consolidación v1.1) — patrón HU-F06 + HU-F09.

---

## 1. Objetivo

Implementar el **ciclo completo de orden Market SELL** **reutilizando el andamio arquitectónico montado por HU-F09**, completando así el flujo de trading bidireccional del MVP:

1. **`POST /api/v1/orders/quote`** — extensión retro-compatible para `side=SELL`: además de los campos heredados, devuelve `sufficientShares` y `userShares`. `estimatedTotal` adopta semántica side-aware (BUY = subtotal + commission; SELL = subtotal − commission, producto neto).
2. **`POST /api/v1/orders`** — rama `side=SELL` activada en `TradingService.placeOrder`: validaciones nuevas (`SHORT_SELLING_NOT_ALLOWED`, `INSUFFICIENT_SHARES`) → INSERT order PENDING → submit Alpaca con `side=sell` → UPDATE order EXECUTED/REJECTED/FAILED/PENDING+alpacaOrderId → decrement `app.positions` (DELETE si `quantity=0`) + credit `app.user_balances` → COMMIT → notify (template SELL) + audit (mismos events con `side=SELL` en details).

**NO materializa componentes arquitectónicos nuevos.** Es la HU que **valida retrospectivamente el diseño de F09**: el andamio resultó suficiente porque F10 solo extiende contratos existentes (servicios, notificaciones, eventos) sin agregar módulos.

Lo nuevo respecto a F09:
- 2 excepciones (`ShortSellingNotAllowedException`, `InsufficientSharesException`) + 2 handlers en `GlobalExceptionHandler`.
- 2 métodos en `PortfolioService` (`credit`, `decrementPosition`).
- 3 métodos en `Notifier` (`notifyOrderExecutedSell`, `notifyOrderRejectedSell`, `notifyOrderFailedSell`) + 3 templates Thymeleaf nuevas + rename de 3 templates F09 a sufijo `-buy`.
- Extensión a `TradingService` (dispatch interno por side, validaciones SELL, `OrderEventListener` distingue side para elegir template).
- 2 campos nuevos en `QuoteResponse` (`sufficientShares`, `userShares`).
- 2 códigos nuevos en `messages.es.ts`.
- Frontend: habilitar toggle SELL en `OrderForm`, branching side-aware en `OrderQuotePanel` + `OrderConfirmationToast`.

**Tamaño estimado:** **~0.5–1 día efectivo** (~40-50% del esfuerzo de F09, consistente con AGENTS.md "Estimar complejidad" §5). Lote A (PortfolioService + excepciones) es ~25%; Lote B (TradingService dispatch + handlers) ~25%; Lote C (notifier + templates) ~15%; Lote D (tests IT) ~20%; Lote E (frontend) ~15%; Lote F (cierre) trivial.

---

## 2. Decisiones técnicas concretas

> D1–D4 son las cerradas por el cuestionario humano antes de redactar el SPEC. D5–D11 son las 7 que el SPEC §14 dejó diferidas — ahora se cierran. D12–Dxx serán emergentes durante implementación (siguiendo el patrón D23–D29 de F09).

### 2.1 Decisiones cerradas por el cuestionario humano (D1–D4)

| # | Decisión | Justificación |
|---|---|---|
| **D1** | **DELETE de la fila de `app.positions` cuando `quantity` resultante = 0.** No mantener fila con `quantity=0`. | (a) Comportamiento estándar de brokers reales — la posición desaparece al liquidarla. (b) HU-F16 `GET /portfolio/positions` queda más limpio: lista solo tenencia real sin filtro adicional `WHERE quantity > 0`. (c) Trade-off aceptado: si el usuario vende todo y vuelve a comprar el mismo ticker, pierde el `avg_buy_price` histórico — el nuevo se recalcula desde cero en F09 `upsertPosition`. Para MVP académico es correcto. |
| **D2** | **`SHORT_SELLING_NOT_ALLOWED` server-side bloqueante.** BloomTrade es long-only por diseño. Si no existe fila en `app.positions` para `(userId, ticker)` o `quantity=0` → 409. | (a) ARCH no menciona shorts; F09 asumió long-only implícitamente. (b) Alpaca paper SÍ permite shorts pero BloomTrade los rechaza por simplicidad de invariantes (`quantity >= 0` en BD). (c) Permite eliminar todo el branching de "posición con cantidad negativa" en `PortfolioService` y `OrderOrchestrator`. |
| **D3** | **Reutilizar `POST /api/v1/orders` con `side=SELL`.** F09 ya dejó el endpoint preparado: el OpenAPI acepta `BUY|SELL`, devolvía 400 `SIDE_NOT_YET_IMPLEMENTED` en SELL. F10 habilita el handler real. | (a) Diseño REST single-endpoint coherente (`/orders` es la colección de órdenes, no `/orders/buy` y `/orders/sell`). (b) Frontend reutiliza `useSubmitOrder` hook sin cambios estructurales. (c) Idempotencia (`client_order_id`), retries, audit, error mapping — todo reutilizado intacto. |
| **D4** | **Templates Thymeleaf separadas por side**, NO condicional `th:if=side`. F10 crea `order-executed-sell.html` / `order-rejected-sell.html` / `order-failed-sell.html`, y **renombra** las 3 templates F09 a sufijo `-buy` (D6 abajo profundiza). | (a) Wording difiere materialmente ("se descontaron USD X" vs "recibiste USD X"; "total a pagar" vs "producto neto"). (b) Templates con condicionales mezclan copy con lógica — peor mantenibilidad. (c) `git mv` deja history limpio del rename. (d) Tests del notifier hacen assert sobre template name, más legibles. |

### 2.2 Decisiones cerradas — diferidas en SPEC §14 (D5–D11)

| # | Decisión | Justificación |
|---|---|---|
| **D5 — D-TRADING-METHOD** | **`TradingService.placeOrder` única con dispatch interno por `request.side()`.** NO métodos separados `placeOrderBuy` + `placeOrderSell`. La parte común (idempotency lock, INSERT order, submit Alpaca, listener post-commit, manejo de excepciones Alpaca) vive en el método principal; un método helper privado por side maneja las validaciones específicas + la mutación a `app.positions` y `app.user_balances`. | (a) ~70% del flujo es común. Métodos separados duplicarían el INSERT + Alpaca submit + idempotency lock. (b) El `OrderController` ya despacha por `side` solo en la respuesta HTTP — la fragmentación a nivel de service rompe la cohesión. (c) Test de regresión más sólido: cambios al flujo común (ej: nuevo header Alpaca) impactan ambos paths simultáneamente. (d) Patrón usado por F09 D29 con `OrderQueuedEvent` — extender es natural. |
| **D6 — D-EMAIL-RENAME-F09** | **Renombrar las 3 templates F09 (`order-executed.html`, `order-rejected.html`, `order-failed.html`, `order-queued.html`) a sufijo `-buy`** y crear las 3 (o 4 con queued) `-sell` correspondientes. Cambio en `MailNotifier` para apuntar a las rutas nuevas. | (a) Simetría arquitectónica: si SELL tiene sufijo, BUY también lo necesita. (b) Sin sufijo invita ambigüedad ("¿`order-executed.html` es la genérica o solo BUY?"). (c) `git mv` mantiene history. (d) Sin breaking change externo — el nombre del template es interno. (e) **Verificación previa**: confirmar también `order-queued-buy.html` heredado de D29 — F09 H.5 ya lo nombró así. |
| **D7 — D-NOTIFIER-SPLIT** | **`Notifier` con 6 métodos** (3 BUY heredados + 3 SELL nuevos): `notifyOrderExecutedBuy/Sell`, `notifyOrderRejectedBuy/Sell`, `notifyOrderFailedBuy/Sell`. **Rename de los 3 métodos F09 sin sufijo a `*Buy`**. NO 3 métodos parametrizados por `OrderSide`. | (a) Firmas explícitas hacen tests del listener más declarativos (`verify(notifier).notifyOrderExecutedSell(...)`). (b) Wording de templates difiere — pasar el side como parámetro obligaría a un `switch` interno en `MailNotifier`. (c) Notifier es interfaz pequeña (actualmente 4-5 métodos tras F06+F09); 6 sigue siendo manejable. (d) Si en el futuro se agregan más sides (no contemplado), refactor a parametrización es local. |
| **D8 — D-PORTFOLIO-DECREMENT-RETURN** | **`PortfolioService.decrementPosition(userId, ticker, sellQuantity)` retorna `Optional<Position>`**: `Optional.empty()` si la fila fue borrada (qty resultante = 0), `Optional.of(updated)` si quedó tenencia. | (a) Comunica explícitamente "la posición ya no existe" sin convención mágica (`quantity=0` significa borrada). (b) Caller (`OrderOrchestrator`) usa `.isPresent()` para llenar `ORDER_EXECUTED.details.positionDeleted` (D11). (c) Pattern consistente con Spring Data — Optional para "puede no existir". |
| **D9 — D-SELL-QUEUED-RISK** | **Heredar comportamiento D29 F09 en SELL**: si Alpaca responde `accepted` (no `filled`) tras polling sync, la orden queda `status='PENDING' + alpacaOrderId`. **La posición se decrementa optimistamente; el balance NO se acredita aún** (no sabemos el precio real de fill). Email + audit `ORDER_QUEUED` análogos a BUY (template SELL). | (a) Simetría con F09 — pivotear (ej: "bloquear SELL si Alpaca no responde fill inmediato") rompería el modelo unificado de placeOrder. (b) Trade-off explícito: si Alpaca cancela la encolada después, el usuario perdió posición sin recibir crédito (edge case extremo). Probabilidad muy baja en testing single-user en horario de mercado. (c) **Extiende deuda AGENTS.md #8 + #13**: el job de reconciliation Alpaca↔BloomTrade tendría que actualizar tanto `balance` (acreditar cuando filea) como `positions` (re-incrementar si Alpaca cancela). Job nocturno o webhook handler en post-MVP. (d) Documentado claramente en email queued: "Tu orden de venta se encoló; recibirás el crédito al ejecutarse en la apertura." |
| **D10 — D-UI-FILTER-SELL-DROPDOWN** | **NO filtrar `TickerDropdown` por posiciones del usuario en SELL durante MVP F10.** Dropdown sigue mostrando los 25. Validación 100% server-side. | (a) Filtrar requeriría `GET /portfolio/positions` que es HU-F16 (Día 8). Crear endpoint stub solo para F10 es trabajo desperdiciado. (b) UX server-side con `SHORT_SELLING_NOT_ALLOWED` claro es suficiente para usuario testing MVP. (c) **Promoción documentada como deuda futura**: cuando F16 mergee, agregar feature flag o extender hook `useTickerOptions` para filtrar por posiciones. Registrar como deuda en AGENTS.md. |
| **D11 — D-AUDIT-POSITION-DELETED-FIELD** | **Incluir `positionDeleted: boolean` + `positionResultingQty: integer` en `ORDER_EXECUTED.details` para SELL.** Para BUY, solo `positionResultingQty` (cantidad post-upsert). | (a) `positionDeleted=true` permite query Kibana "ventas que liquidaron posición completa" — métrica útil para análisis. (b) `positionResultingQty` permite reconstruir historial de posiciones SQL-less desde solo audit logs. (c) Tamaño de payload aumenta ~30 bytes — insignificante. (d) Sin event type nuevo (`POSITION_DEPLETED`) → catálogo audit sigue compacto en 7 entries. |

### 2.3 Decisiones emergentes del diseño (D12–Dxx)

| # | Decisión | Justificación |
|---|---|---|
| **D12 — D-LOCK-ORDER** | **Orden de adquisición de locks pessimistic consistente**: primero `app.user_balances` (lock heredado de F09 `findByUserIdForUpdate`), después `app.positions` (lock nuevo `findByUserIdAndTickerForUpdate`). **AMBAS** ramas BUY y SELL adquieren los locks en el mismo orden (BUY no toca `app.positions` en pre-validation pero sí en post-Alpaca para upsert; SELL los necesita ambos desde validación). | Prevención de deadlock entre dos órdenes concurrentes del mismo usuario sobre el mismo ticker (ej: BUY + SELL simultáneos de AAPL). Si BUY tomara primero positions y SELL primero balances, podrían bloquearse mutuamente. Convención explícita evita esto. Documentar en javadoc de `PortfolioService`. Test IT específico verifica ausencia de deadlock con BUY + SELL concurrentes. |
| **D13 — D-NO-MIGRATION-VERIFICATION** | **Lote A arranca con verificación pre-coding** que confirma que V5 ya cubre F10: `psql -c "\d app.orders"` muestra `chk_order_side CHECK (side IN ('BUY', 'SELL'))` y `psql -c "\d app.positions"` muestra `chk_position_quantity CHECK (quantity >= 0)`. Si el output difiere → STOP, crear V6 correctiva. | F09 SPEC §7.2 anticipó F10 explícitamente. Esta verificación valida que la decisión retrospectivamente sí se materializó (defensa contra "el código se desvió del SPEC sin que nadie notara"). Toma ~30s de un psql; vale la pena por la confianza. |
| **D14 — D-CREDIT-NOROLLBACK** | **`PortfolioService.credit` NO necesita `noRollbackFor`** (a diferencia de `debit` D24 F09). Si la SELL falla por excepción no-Alpaca (ej: posición desapareció entre validation y mutation), el rollback de la transacción debe revertir todo incluido el credit. | `debit` tenía `noRollbackFor=InsufficientFundsException` porque el caller quería preservar la fila `app.orders` con `status=FAILED/REJECTED` mientras lanzaba excepción de validación. `credit` no tiene un análogo: si la venta falla, NO hay nada que "preservar" — el rollback debe ser limpio. Mantener semántica default de Spring transaction = menos cognitive load. |
| **D15 — D-POSITION-LOCK-ON-QUOTE** | **NO tomar lock pessimistic sobre `app.positions` durante quote.** Solo en `placeOrder` SELL (defensa post-validation). El quote es informativo — bloquear filas durante quotes degrada performance. | (a) Consistencia con F09: quote tampoco bloquea `user_balances`. (b) Race entre quote y submit es aceptada: si entre el quote y el submit otra venta consume la posición, el submit falla con `INSUFFICIENT_SHARES` (escenario Gherkin cubierto). (c) Performance: quote debe responder <2s p95. |
| **D16 — D-COVERAGE** | **Coverage target ~60-65%** [[feedback-coverage-vs-velocidad]]. Foco crítico: `PortfolioService.credit/decrementPosition` (BigDecimal exact + DELETE behavior), `TradingService.placeOrderSell` (dispatch interno + validaciones SELL), `OrderEventListener` (despacho a método Notifier correcto por side). Skip: tests triviales de DTOs + mappers; tests duplicados del flujo común BUY ya cubiertos por F09. | Memoria viva. Reuso de tests F09 vía herencia conceptual — la concurrencia y idempotencia son los mismos. |

### 2.4 Decisiones emergentes durante implementación (D17–D21)

> Patrón heredado de HU-F09 §2.3 D23–D29: las decisiones que aparecen durante la implementación se documentan acá, no en chat ni solo en commits. Cada una incluye su lote de origen y la causa raíz que la disparó.

| # | Decisión | Lote | Justificación / causa raíz |
|---|---|---|---|
| **D17 — D-LOCK-CANONICAL-ORDER** ⚠️ corrige D12 | **Orden canónico de locks pessimistic: `app.user_balances` PRIMERO, después `app.positions`** — aplicado en `PortfolioService.validateSellable` (HU-F10) y en `debit + upsertPosition` (HU-F09). El javadoc original de D12 era impreciso: asumía que BUY y SELL "no competían" por los mismos 2 locks (BUY bloquearía solo balances, SELL solo positions), lo que es falso. **Real**: BUY adquiere balances en `debit` y después toca positions en `upsertPosition` (vía Hibernate dirty checking del entity persistido); SELL adquiría positions en `validateSellable` y después balances en `credit`. Orden inverso → ciclo → `deadlock detected` reportado por Postgres en `TradingServiceSellConcurrencyIT#concurrency_buyAndSellSameTicker_*`. **Fix**: `validateSellable` ahora invoca `userBalanceRepository.findByUserIdForUpdate(userId)` PRIMERO (toma lock balances aunque la venta no requiera pre-validar saldo) y después el `findByUserIdAndTickerForUpdate(...)` de positions. Trade-off aceptado: dos SELLs concurrentes del mismo usuario sobre tickers distintos se serializan en balances (no necesario funcionalmente, pero MVP single-user lo absorbe). | D | Bug descubierto por test concurrencia D-12; reproducible y mitigado mismo lote. Documentado en javadoc de `PortfolioService` (clase + método). |
| **D18 — D-NOROLLBACK-VALIDATE-SELLABLE** | **`@Transactional(noRollbackFor={ShortSellingNotAllowedException, InsufficientSharesException})` en `PortfolioService.validateSellable` y `decrementPosition`**, análogo a D24/D27 F09 sobre `debit` con `InsufficientFundsException`. Sin esta anotación, cuando `validateSellable` (anidado en `placeOrderTx`) lanza una de las 2 excepciones, Spring marca la TX outer como **rollback-only** por default — el commit posterior falla con `UnexpectedRollbackException` (HTTP 500 en lugar del 409 esperado). El `noRollbackFor` del outer `placeOrderTx` no alcanza porque la decisión de rollback ya se tomó en el método inner. | D | Bug descubierto en `TradingControllerSellIT` (7/9 pasaban, 2 daban 500). Patrón idéntico a D27 F09 — confirma que el `noRollbackFor` debe declararse en TODO método que pueda lanzar la excepción que se quiere preservar. |
| **D19 — D-LISTENER-SKIP-SELL-EMAIL-DEFERRED** | **Refactor en dos fases del email dispatch SELL**: Lote B deja el listener skip-eando emails SELL con un `log.info("SELL email send skipped (pendiente Lote C D7)")`; Lote C completa el rename del `Notifier` a `*Buy/*Sell` y habilita dispatch real. Audit logs SI se emiten en ambas fases. | B+C | Trade-off pragmático: el refactor de `TradingService` (Lote B) tocaba ~12 archivos y completar el rename del `Notifier` simultáneamente lo habría hecho ingobernable. Separar en lotes con HITOs aislados redujo el riesgo de regresión. Los tests de Lote B/C atrapan ambas fases independientemente. |
| **D20 — D-AUDIT-SIDE-EVERYWHERE** | **`side` se incluye en el `details` de TODOS los 5 audit events de orden** (`ORDER_CREATED`, `ORDER_EXECUTED`, `ORDER_REJECTED`, `ORDER_FAILED`, `ORDER_QUEUED`), no solo en `ORDER_EXECUTED` como propuso D11 del SPEC. | B | D11 era estricto sobre `positionResultingQty + positionDeleted` (que sí siguen solo en `ORDER_EXECUTED`) pero subestimó que `side` es atributo universal del audit log — sin él, query Kibana "todas las ventas que fueron rejected hoy" requiere correlacionar con `app.orders` BD en lugar de filtrar solo en ElasticSearch. Costo: ~5 bytes por evento. |
| **D21 — D-LISTENER-DISPATCH-OWNS-SWITCH** | **El dispatch por `side` vive en `OrderEventListener`, no como default method en la interface `Notifier`**. Es decir, `Notifier` expone 8 métodos explícitos (`sendOrderExecutedEmailBuy/Sell`, etc.) sin un envelope `sendOrderExecutedEmail(side, ...)` que delega internamente. | C | (a) Tests mock más declarativos: `verify(notifier).sendOrderExecutedEmailSell(...)` lee mejor que `verify(notifier).send(eq(OrderSide.SELL), ...)`. (b) `Notifier` se mantiene como interface pura (sin lógica). (c) El único caller del dispatch es `OrderEventListener` — fragmentar la decisión en 2 lugares (interface + caller) sería duplicación gratuita. |

---

## 3. Cambios de dependencias

**Backend (`pom.xml`):** NINGUNO. Spring `RestClient`, Resilience4j, WireMock, Thymeleaf — todo presente desde F06+F09.

**STACK.md:** NINGÚN cambio. Sin librerías nuevas, sin proveedores externos nuevos.

**Frontend (`package.json`):** NINGUNO. Reusa axios, react-query, react-router-dom, RHF+zod.

**`.env` y `.env.example`:** NINGÚN cambio. Reusa las 5 vars `ALPACA_*` + `TRADING_DEFAULT_COMMISSION_PCT` de F09.

**`docker-compose.yml`:** NINGÚN cambio.

**Setup manual del humano antes del Lote D (tests IT) o HITO 5 (smoke E2E):**
1. **Verificación pre-Lote A** (D13): `docker compose exec postgres psql -U bloomtrade -c "\d app.orders"` y confirmar `chk_order_side CHECK (side IN ('BUY', 'SELL'))`. Si no, crear V6 (no debería pasar).
2. **Pre-HITO 5 demo manual** (solo si mercado NYSE abierto): tener al menos 1 posición en `app.positions` del usuario testing — la del demo F09 sirve. Si no hay: ejecutar primero una BUY de AAPL × 5 vía /trade.

---

## 4. Reuso de HUs previas y cosas nuevas

**Reutilizado tal cual (sin modificaciones):**

- Migración V5 (orders + positions + commission_rates) — D13 verifica.
- `IntegrationConfig` + `AlpacaTradingAdapter` (`submitMarketOrder` ya soporta `side="sell"`) + `MarketDataAdapter`.
- `CommissionManager` + `ConfigurationManager` + `MarketScheduleManager` (stub).
- `OrderRepository` + `Order` entity + `OrderSide`/`OrderType`/`OrderStatus` enums.
- `OrderMapper`.
- `AuditEventType` (7 entries de F09 cubren F10).
- `GlobalExceptionHandler` (extender con 2 handlers; reusa `ErrorResponse`).
- `JwtAuthenticationFilter`, `AuthenticatedUser`, `User` + status checks — sin cambios.
- `AllowedTickers` (los 25) — sin cambios.

**Extendido (modificación a archivos F09):**

```
backend/src/main/java/co/edu/unbosque/bloomtrade/

├── trading/
│   ├── service/TradingService.java          ← + rama side=SELL en placeOrderTx + quote SELL
│   ├── service/OrderOrchestrator.java       ← + secuencia post-Alpaca para SELL (decrement + credit)
│   ├── controller/OrderController.java      ← sin cambios estructurales (el body acepta SELL)
│   ├── dto/QuoteResponse.java               ← + sufficientShares + userShares + javadoc side-aware
│   ├── exception/                            ← + ShortSellingNotAllowedException
│   │                                          + InsufficientSharesException
│   └── event/OrderEventListener.java        ← + dispatch por side a método Notifier correcto

├── portfolio/
│   ├── repository/UserBalanceRepository.java ← (sin cambios — credit reusa lock heredado)
│   ├── repository/PositionRepository.java   ← + findByUserIdAndTickerForUpdate (lock pessimistic)
│   │                                          + deleteByUserIdAndTicker
│   └── service/PortfolioService.java        ← + credit(userId, BigDecimal)
│                                              + decrementPosition(userId, ticker, sellQty) → Optional<Position>

├── notification/
│   └── service/{Notifier, MailNotifier}.java ← + 3 métodos *Sell
│                                               + rename de 3 métodos F09 a *Buy

└── shared/web/
    ├── GlobalExceptionHandler.java          ← + 2 handlers (409 SHORT_SELLING_NOT_ALLOWED, 409 INSUFFICIENT_SHARES)
    └── ValidationMessages.java              ← + 2 códigos
```

**Templates Thymeleaf** (`backend/src/main/resources/templates/`):

```
RENAME (D6):
order-executed.html       → order-executed-buy.html
order-rejected.html       → order-rejected-buy.html
order-failed.html         → order-failed-buy.html
order-queued.html         → order-queued-buy.html      (si existe — D29 F09 H.5)

NUEVOS:
order-executed-sell.html
order-rejected-sell.html
order-failed-sell.html
order-queued-sell.html    (heredando D9 — análogo a queued-buy)
```

Cambio en `MailNotifier` para apuntar a las rutas nuevas (afecta los 3 métodos `*Buy` renombrados y agrega 3 `*Sell`).

**Frontend** (`frontend/src/`):

```
features/trading/
├── components/
│   ├── OrderForm.tsx              ← quitar `disabled` del toggle SELL + tooltip dropdown
│   ├── OrderQuotePanel.tsx        ← branching por side: wording, color, línea "Posición restante"
│   └── OrderConfirmationToast.tsx ← branching por side en wording
│
└── hooks/useSubmitOrder.ts        ← + manejo de SHORT_SELLING_NOT_ALLOWED + INSUFFICIENT_SHARES en onError

types/api.ts                       ← + sufficientShares + userShares en QuoteResponse
i18n/messages.es.ts                ← + SHORT_SELLING_NOT_ALLOWED + INSUFFICIENT_SHARES
```

`TickerDropdown.tsx`, `useQuote.ts`, `tradingApi.ts`, `TradePage.tsx`, `App.tsx`, `AppHeader.tsx` — **sin cambios**.

**Migración Flyway:** NINGUNA (D13 lo verifica al arrancar).

---

## 5. Orden de lotes (resumen — detalle en `tasks.md`)

| Lote | Resumen | HITO de validación |
|---|---|---|
| **A** | Verificación pre-coding D13. `PortfolioService.credit` + `PortfolioService.decrementPosition` (con `Optional<Position>` retorno, D8). Extensión a `PositionRepository` (`findByUserIdAndTickerForUpdate`, `deleteByUserIdAndTicker`). 2 excepciones nuevas. | **HITO 1**: `mvn compile` verde + `PortfolioServiceTest` nuevos casos (credit happy, credit BigDecimal exact, decrement happy con qty residual, decrement con DELETE, decrement con qty insuficiente, decrement sin posición). |
| **B** | `TradingService.placeOrder` con dispatch interno por side (D5): quote SELL devuelve `sufficientShares`/`userShares`, `placeOrderTx` ramifica a `handleSellTx` / `handleBuyTx`. `OrderOrchestrator` extiende secuencia post-Alpaca para SELL (decrement + credit + audit details positionDeleted/positionResultingQty). Lock order D12 documentado. 2 handlers nuevos en `GlobalExceptionHandler`. 2 códigos en `ValidationMessages`. | **HITO 2**: compile + `TradingServiceTest` ≥6 escenarios nuevos (quote SELL con/sin posición/insuficiente, placeOrder SELL OK con qty residual, placeOrder SELL DELETE, placeOrder SELL SHORT_SELLING_NOT_ALLOWED, placeOrder SELL INSUFFICIENT_SHARES). |
| **C** | `Notifier` extendido con 3 métodos `*Sell` (D7). `MailNotifier` implementa los 3 + rename de los 3 (4 con queued) `*Buy`. Rename de templates F09 vía `git mv` (D6). 3 (4) templates Thymeleaf nuevas `-sell` con inline-CSS estilo F09. `OrderEventListener` ramifica por `event.side()` para elegir método. | **HITO 3**: compile + `OrderEventListenerTest` verifica dispatch correcto por side (mock notifier, assert método correcto invocado). `MailNotifierTest` verifica que cada template renderiza con datos válidos. |
| **D** | Tests IT con WireMock: 7 escenarios SELL (happy → EXECUTED, happy → DELETE position, Alpaca down, Alpaca rechaza, SHORT_SELLING_NOT_ALLOWED, INSUFFICIENT_SHARES, idempotencia × 5 mismo SELL, concurrencia × 2 SELL del mismo ticker). Test BUY+SELL concurrentes mismo ticker (D12 lock order). | **HITO 4**: `mvn verify` con TODOS los IT verdes (los heredados de F09 + los nuevos F10). |
| **E** | Frontend: `OrderForm` habilita SELL toggle + tooltip. `OrderQuotePanel` branching por side (wording, color, línea "Posición restante"). `OrderConfirmationToast` branching. `useSubmitOrder.ts` maneja 2 códigos nuevos. `types/api.ts` extiende `QuoteResponse`. `messages.es.ts` +2 códigos. | **HITO 5**: `npm run build` verde + **smoke E2E manual** del humano (idealmente con NYSE abierto). Demo: usuario con posición AAPL (de F09) → /trade → toggle SELL → AAPL × N (N ≤ posición) → quote → confirmar → toast emerald "Vendiste — recibiste USD X" + MailHog tiene email -sell + Kibana tiene `ORDER_EXECUTED` con `details.side=SELL` y `details.positionDeleted` poblado + posición decrementada o borrada + balance acreditado. |
| **F** | Cierre: `APRENDIZAJES.md` sección "Día 7 — HU-F10" en primera persona con reflexiones técnicas (mínimo 5: validación retrospectiva del andamio F09, complejidad real vs estimada, lock order BUY+SELL concurrente, D29 heredado en SELL con riesgo). SPEC v1.1 consolidado si emergieron D17+ durante implementación. `AGENTS.md` handoff actualizado: cierre F10, próximo paso HU-F16+F21 Día 8. Mensaje de commit preparado en `C:\Users\juang\AppData\Local\Temp\bt-hu-f10.txt` (P6 ruta completa). | **HITO 6**: commit listo + push + PR creado. Humano firma. Squash and merge a `main`. |

---

## 6. Riesgos identificados

| Riesgo | Probabilidad | Impacto | Mitigación |
|---|---|---|---|
| V5 no incluye `'SELL'` en `chk_order_side` por bug histórico que nadie notó | Baja | Alto | D13 verifica pre-coding. Si pasa: crear V6 ALTER constraint. Tiempo perdido: ~10 min. |
| `AlpacaTradingAdapter.submitMarketOrder` no envía `side="sell"` correctamente cuando recibe `OrderSide.SELL` (typo en mapping enum→string) | Media | Alto | Lote A agrega test unitario explícito: mock RestClient, llamar con `OrderSide.SELL`, assert que el body JSON contiene `"side":"sell"`. Si falla en HITO 2, fix es trivial. |
| Deadlock entre BUY y SELL concurrentes del mismo usuario+ticker por orden de locks inconsistente | Media | Medio | D12 define orden canónico + javadoc + test IT específico en Lote D. Si emerge en producción: el test reproduce. |
| Sin posición disponible al momento del HITO 5 smoke E2E (la del demo F09 quedó `PENDING+alpacaOrderId` por mercado cerrado) | Alta | Bajo | Pre-HITO 5: ejecutar una BUY AAPL × 5 con mercado abierto. Si NYSE cerrado el día de F10 (martes 26 May ya es post-Memorial Day, debería estar abierto desde las 8:30 AM hora COL), entonces la BUY queda `PENDING` también y no podremos hacer SELL — postergar el HITO 5 hasta el martes/miércoles siguiente. |
| `PositionRepository.deleteByUserIdAndTicker` lanza `EmptyResultDataAccessException` si la fila ya fue borrada por una transacción concurrente | Baja | Bajo | Spring Data JPA `deleteBy*` retorna void; usar `@Modifying @Query` con `int` retorno + handle de 0 rows si necesario. Test IT con 2 SELLs idénticos concurrentes verifica. |
| `OrderEventListener` invoca método Notifier incorrecto por bug de switch | Baja | Medio | Test unitario explícito en Lote C: mock notifier, dispatch evento con `side=SELL`, verify que solo `notifyOrderExecutedSell` fue invocado (NUNCA `*Buy`). |
| Rename de templates F09 rompe tests F09 existentes (alguno hace assert sobre nombre) | Media | Bajo | Lote C ejecuta `mvn verify` completo; los tests F09 que rompan se actualizan en el mismo commit. Limpio en git como rename + test update. |
| SELL en mercado cerrado (Alpaca `accepted`) decrementa posición pero nunca acredita balance porque reconciliación post-MVP no existe — usuario reporta bug | Baja | Medio | D9 documentado en SPEC §5.1 paso 14 + plan §2.2. Email "se encoló" educa al usuario. Si pasa en demo: explicación clara + log en Kibana con `alpacaOrderId` para reconciliación manual. |
| Concurrencia: dos SELLs simultáneos del usuario consumen la posición y el segundo falla con error genérico en lugar de 409 limpio | Media | Bajo | Test IT × 2 hilos en Lote D. Lock pessimistic D12 garantiza serialización; el segundo lee la cantidad post-commit del primero y rechaza limpio. |
| Frontend hace SELL con `quantity > userShares` pese a UX deshabilitar — bypass DOM | Baja | Bajo | Server-side `INSUFFICIENT_SHARES` cubre. Test E2E manual no esperable; test IT del backend es la garantía. |

---

## 7. Validaciones de DoD por HITO

- **HITO 1 (Lote A)**: `mvn compile` + `psql` confirma V5 ya soporta F10 + `PortfolioServiceTest` 6 casos nuevos verdes (credit happy, credit precision BigDecimal, decrement con qty residual, decrement con DELETE, decrement con InsufficientShares, decrement con ShortSelling). `PositionRepository` test verifica lock toma efecto (2 threads).
- **HITO 2 (Lote B)**: compile + `TradingServiceTest` 6 escenarios SELL verdes. Swagger UI muestra `QuoteResponse` con `sufficientShares`/`userShares` y descripción side-aware de `estimatedTotal`. `GlobalExceptionHandler` mapea las 2 excepciones nuevas a 409 con códigos correctos (verificable con curl manual al endpoint o test integration). Test específico verifica que `AlpacaTradingAdapter.submitMarketOrder` envía `side="sell"` cuando recibe `OrderSide.SELL`.
- **HITO 3 (Lote C)**: compile + `OrderEventListenerTest` verifica dispatch correcto por side. Las 3-4 templates `-sell` nuevas renderizan con datos de prueba sin errores Thymeleaf. Rename de F09 templates aplicado en git history como `R` (rename); tests F09 que asserten template name actualizados.
- **HITO 4 (Lote D)**: `mvn verify` verde con suite IT completa (heredados F09 + 8 nuevos F10): happy SELL → EXECUTED, happy SELL → DELETE, Alpaca down SELL, Alpaca rechaza SELL, SHORT_SELLING_NOT_ALLOWED, INSUFFICIENT_SHARES, idempotencia × 5 SELL, concurrencia × 2 SELL mismo ticker, BUY+SELL concurrente mismo ticker (lock order D12).
- **HITO 5 (Lote E)**: `npm run build` verde + **smoke E2E humano**: `docker compose up -d --build` → login → /trade → toggle SELL → AAPL × 5 (asumiendo posición ≥ 5 de F09 demo o BUY previa) → quote muestra "Producto neto: USD X" y "Posición restante: Y AAPL" → confirmar → toast emerald "Vendiste 5 AAPL — recibiste USD X" → verificar en MailHog email `order-executed-sell.html` con wording correcto → verificar en Kibana `ORDER_EXECUTED` con `details.side=SELL`, `details.positionDeleted` y `details.positionResultingQty` → `psql -c "SELECT * FROM app.orders WHERE side='SELL' ORDER BY submitted_at DESC LIMIT 1"` muestra `status=EXECUTED` + `execution_total` ≈ `subtotal − commission` → `app.positions` decrementado o borrado → `app.user_balances.balance` incrementado → Alpaca paper dashboard muestra la venta.
- **HITO 6 (Lote F)**: commit message en `C:\Users\juang\AppData\Local\Temp\bt-hu-f10.txt` (ruta completa, P6). APRENDIZAJES.md sección "Día 7 — HU-F10" añadida. AGENTS.md "Trabajo activo" + "Cómo continuar" actualizados con cierre F10 y próximo paso (HU-F16+F21 Día 8). Si emergieron decisiones D17+ durante implementación, SPEC v1.1 consolidado con changelog.

---

## 8. Cambios en documentos maestros

- **STACK.md**: NINGÚN cambio. Sin deps nuevas, sin servicios externos nuevos.
- **ARCHITECTURE.md**: NINGÚN cambio estructural. Opcional: §4 TradingService podría mencionar que SELL completa el flujo bidireccional MVP — cambio cosmético, deferible o no necesario.
- **SPEC.md**: consolidación v1.1 deferida a HITO 6 (Lote F) si emergieron decisiones nuevas (D17+) durante implementación. Mismo patrón F06 v1.2 y F09 v1.1.

---

## 9. Changelog

| Versión | Fecha | Cambio | Razón |
|---|---|---|---|
| 1.0 | 2026-05-23 | Versión inicial. D1–D4 cerradas por cuestionario humano antes del SPEC + D5–D11 cerradas resolviendo las 7 preguntas abiertas del SPEC §14 + D12–D16 emergentes del diseño (D-LOCK-ORDER, D-NO-MIGRATION-VERIFICATION, D-CREDIT-NOROLLBACK, D-POSITION-LOCK-ON-QUOTE, D-COVERAGE). 6 lotes A–F (~40-50% del esfuerzo F09 — consistente con AGENTS.md estimación). Sin nuevas deps, sin nueva migración, sin nuevos módulos. | Cierre del SDD Paso 2 para HU-F10. Pendiente aprobación humana antes de Paso 3 (tasks.md). |
| 1.1 | 2026-05-24 | §2.4 agregada con 5 decisiones emergentes D17–D21 descubiertas durante implementación: D17 corrige el lock canónico que en D12 estaba mal documentado (bug de deadlock atrapado por test BUY+SELL concurrente en Lote D); D18 agrega `noRollbackFor` a `validateSellable + decrementPosition` (síntoma 500 en lugar de 409 atrapado por `TradingControllerSellIT`); D19 documenta el skip provisional de email SELL en Lote B → resuelto en Lote C; D20 amplía D11 (side en TODOS los audit events, no solo `ORDER_EXECUTED`); D21 deja el dispatch por side en el listener, no en el Notifier. | Cierre Lote F HU-F10 — consolidar las decisiones emergentes en el doc autoritativo, no perderlas en commits ni chat. SPEC sin bump (decisiones implementation-detail, contratos API sin cambio). |
