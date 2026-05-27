# spec.md — Cancelar orden Market

---

## 1. Metadatos

| Campo | Valor |
|---|---|
| ID(s) de HU | HU-F15 (BT-19 en Jira) |
| Sprint | 2 — promovida desde stretch goal §3.4 #1 del ROADMAP |
| Prioridad MoSCoW | Should (promovida tras cierre Sprint 2 funcional) |
| Estado | Draft v1.0 |
| Autor | Juan |
| Fecha creación | 2026-05-26 |
| Última actualización | 2026-05-26 |
| Versión spec | 1.0 |
| Día estimado del ROADMAP | Día 11 (post-cierre Día 10) |
| HU predecesoras | HU-F09 (compra), HU-F10 (venta), HU-F16+F21 (portafolio/saldo), HU-F18+F17 (dashboard/historial) — todas mergeadas |

---

## 2. Historia(s) de usuario

### HU-F15 — Cancelar orden Market en cola

**Como** inversionista, **quiero** cancelar una orden Market que quedó en cola en el broker (estado `PENDING` con identificador Alpaca asignado, ej. porque fue colocada fuera del horario de mercado o quedó pendiente en una pre-apertura), **para** liberar los fondos optimistamente debitados (en BUY) o restaurar la posición optimistamente decrementada (en SELL) sin tener que esperar a la apertura del mercado.

### Resumen del alcance

Esta spec cubre el **ciclo de vida completo de la cancelación de una orden Market en estado `PENDING + alpacaOrderId`**, integrándose con el `OrderReconciliationService` v1 (lazy on-GET, implementado Día 10 checkpoint 2) para producir un **v2 que maneja transiciones outbound del broker** (`canceled` / `rejected` / `expired`):

1. **Acción explícita del usuario** — endpoint `POST /api/v1/orders/{id}/cancel` consumido por botones "Cancelar" en `PendingOrdersPanel` (`/portfolio`) y `RecentOrdersWidget` (`/dashboard`).
2. **Solicitud al broker** — `AlpacaTradingAdapter.cancelOrder(alpacaOrderId)` invoca `DELETE /v2/orders/{id}` de Alpaca + polling de 2s al `GET /v2/orders/{id}` hasta ver `status=canceled`. **Patrón canónico async** simétrico al `submitMarketOrder` de F09.
3. **Reverso transaccional en caso polling-OK** (sub-2s):
   - **BUY queued**: `credit` del `quoted_total` al `app.user_balances` (revertir el `debit` optimista de D29 F09).
   - **SELL queued**: `increment` de `app.positions.quantity` por la cantidad cancelada (revertir el decrement optimista de D-SELL-QUEUED-RISK de F10). Si la posición fue eliminada por el decrement, se re-`INSERT` con el `avg_buy_price` preservado en el `Order` original.
   - Order pasa a `status=CANCELED` + `canceled_at=NOW()`.
   - Lock canónico `balances → positions` (heredado D17 F10).
4. **Caso polling-timeout** — Alpaca aceptó el `DELETE` pero no confirmó `canceled` en 2s:
   - Backend marca `cancel_requested_at=NOW()` en la orden (sigue `status=PENDING`).
   - Responde `200 OK` con body `{ status: "PENDING", cancelRequestedAt: "..." }`.
   - Frontend renderiza la fila con `opacity-60` + spinner + label "Cancelando…" + botón disabled.
   - `OrderReconciliationService` v2 (próximo GET `/portfolio` o `/orders`) detecta orden con `cancel_requested_at` set + Alpaca confirma `canceled` → transiciona a `CANCELED` + refund/restore en mismo tx.
5. **Idempotencia implícita por `order.id`** — segunda llamada sobre orden ya `CANCELED` retorna `200` con `alreadyCanceledAt`. Sobre orden `PENDING + cancel_requested_at` retorna el mismo body (no segundo DELETE a Alpaca). Sobre `EXECUTED / REJECTED / FAILED / EXPIRED` retorna `409 ORDER_NOT_CANCELABLE`.
6. **Reconcile lazy v2** — extiende el reconcile de Día 10 para procesar transiciones outbound del broker: `PENDING → CANCELED` (por cancel request del usuario u Alpaca timeout TIF day), `PENDING → REJECTED` (por Alpaca rechaza post-encolado), `PENDING → EXPIRED` (por TIF day expiró sin fill). Cada transición que requiera reverso (CANCELED, EXPIRED) ejecuta refund/restore.
7. **Trazabilidad** — 3 audit events nuevos (`ORDER_CANCEL_REQUESTED`, `ORDER_CANCELED`, `ORDER_EXPIRED`) + 2 templates email nuevos (`order-canceled-buy.html`, `order-canceled-sell.html`).

> **Sobre el caso edge "placeOrder en vuelo"** (orden en pleno `clientOrderLocks` D25 F09): **explícitamente fuera de alcance**. Solo es cancelable una orden con `alpacaOrderId` ya persistido (= Alpaca confirmó recepción). El race condition durante el polling de `submitMarketOrder` es despropocionadamente complejo para el caso de uso MVP single-user — queda como deuda post-MVP.

---

## 3. Contexto y dependencias

### Por qué importa

HU-F15 **cierra el ciclo de vida bidireccional de una orden**. Hasta el Día 10, BloomTrade permite **crear** órdenes (BUY/SELL Market) pero no **anularlas**, dejando al usuario sin recurso si una orden queda encolada y ya no la quiere. El caso de uso real es modesto en MVP (Alpaca paper en horario de mercado ejecuta casi todo inmediato), pero:

- **ARCHITECTURE.md §9** lista explícitamente `Cancelada` entre los estados de una orden (líneas 362-373). HU-F15 cierra la deuda #27 (`OrderStatus` enum solo tenía 4 valores).
- **ROADMAP.md §3.4** marca F15 como promoción #1 cuando hay margen. Sprint 2 funcional cerrado con margen positivo (Día 10 cerrado con 2 días de antelación al hipotético plazo crítico).
- **Reconcile lazy v2** es necesario para cerrar el reverse de `OrderReconciliationService` v1 (deuda #30 del Día 10): cuando Alpaca cancela una orden por timeout TIF day (fuera del control del usuario), v1 no propaga el reverso de balance/position.

Arquitectónicamente, HU-F15 es **mucho menor que F09 o F10** (~30-35% del esfuerzo de F09): el andamio entero existe (`TradingService`, `AlpacaTradingAdapter`, `PortfolioService.credit/upsertPosition`, `OrderEventListener`, `MailNotifier`, `OrderReconciliationService` v1, `OrderRepository`, frontend `PendingOrdersPanel` / `RecentOrdersWidget`). F15 agrega:

- **2 estados nuevos** al enum `OrderStatus`: `CANCELED`, `EXPIRED`.
- **2 columnas nuevas** a `app.orders`: `cancel_requested_at`, `canceled_at` (+ implicitamente `expired_at` reuso de `executed_at`/`updated_at`).
- **1 endpoint nuevo**: `POST /api/v1/orders/{id}/cancel`.
- **1 método nuevo** al `AlpacaTradingAdapter`: `cancelOrder(alpacaOrderId)` con polling.
- **1 método nuevo** al `TradingService`: `cancelOrder(userId, orderId)` con dispatch BUY/SELL para el reverse.
- **`OrderReconciliationService` v2**: extiende v1 con 3 transiciones outbound (`canceled`, `rejected`, `expired`).
- **3 audit events + 2 templates email + UI components**.

Materializa la misma TAC-S5 (Validar entradas) y TAC-I1 (`OrderOrchestrator`) de F09/F10. Refuerza **TAC-D1 (Idempotencia)** con la idempotencia implícita por `order.id` (sin requerir clave externa).

### Dependencias técnicas

- **HU-F09 + HU-F10 + HU-F16+F21 + HU-F18+F17 mergeadas** — todo el andamio en `main`.
- **`OrderReconciliationService` v1 mergeada** — Día 10 checkpoint 2. F15 lo extiende a v2 (no lo reescribe).
- **Última migración aplicada en main: V5** (F09). F15 introduce V6 — verificado pre-coding Lote A (D25).
- **`AlpacaTradingAdapter`** existe con `submitMarketOrder` + `getOrder`. F15 agrega `cancelOrder`.
- **`PortfolioService.credit(userId, amount)`** existe (introducida en F10 Lote A) — se reutiliza intacta para el refund BUY.
- **`PortfolioService.upsertPosition(userId, ticker, qty, avgBuyPrice)`** existe — se reutiliza para el restore SELL (incluyendo el caso re-INSERT si fila fue eliminada).
- **`OrderRepository`** existe con `findByUserIdAndStatusAndAlpacaOrderIdIsNotNull...` (introducida F16 D-A-T11). F15 agrega `findByIdAndUserId(orderId, userId)`.
- **Frontend `PendingOrdersPanel`** y **`RecentOrdersWidget`** existen — se les agrega botón Cancelar.

### Variables de entorno nuevas

**Ninguna.** F15 usa las mismas Alpaca creds (`ALPACA_API_KEY`, `ALPACA_API_SECRET`, `ALPACA_BASE_URL`) y la misma `alpacaApi` retry policy.

### Migraciones BD nuevas

**V6 `__add_canceled_expired_status_and_cancel_columns.sql`** — extiende `chk_order_status` constraint + agrega 2 columnas. Migración aditiva, sin DDL destructivo. Detalle en §7.

### Features que dependen de esta

- **Ninguna nueva en MVP.** F15 es promoción terminal del Sprint 2. Post-MVP, el revamp UI dependerá de F15 ya estar mergeada.

---

## 4. Actores y precondiciones

### Actores involucrados

| Actor | Rol del sistema | Participación |
|---|---|---|
| Usuario autenticado | INVESTOR | Iniciador de la cancelación |
| Sistema BloomTrade | — | Validador, persistente, orquestador |
| Alpaca Markets | Externo | Ejecutor de la cancelación (paper trading) — `DELETE /v2/orders/{id}` |
| Alpaca paper background | Externo | Genera transiciones outbound (TIF day expira, cancel async, etc.) — F15 las propaga via reconcile lazy v2 |
| MailHog (dev) | Externo | Receptor de notificación email |
| ElasticSearch | Externo | Receptor de audit events |

### Precondiciones del sistema

- Usuario tiene sesión JWT activa con `rol = INVESTOR` y `estado = ACTIVE`.
- Existe fila en `app.orders` con: `user_id = <usuario>`, `id = {id}`, `status = 'PENDING'`, `alpaca_order_id IS NOT NULL`.
- Variables Alpaca pobladas (heredadas F09).
- `OrderReconciliationService` v1 ya activo (cubre la transición happy-path `PENDING → EXECUTED`).

### Datos requeridos en el sistema

- `app.orders` con la orden a cancelar.
- Si la orden es BUY: `app.user_balances` debitado optimistamente por `quoted_total` (D29 F09). Si era SELL: `app.positions` decrementado optimistamente (D-SELL-QUEUED-RISK F10). El reverso restaura el estado previo.

---

## 5. Flujos

### 5.1 Flujo principal — cancelar orden BUY queued (polling-OK <2s)

#### Paso 1: Usuario inicia la cancelación

1. Usuario autenticado navega a `/portfolio` y ve la sección "Órdenes en cola" del `PendingOrdersPanel`.
2. Una fila muestra: `BUY 5 AAPL @ $200 (queued)` con botón "Cancelar".
3. Usuario hace click en "Cancelar".
4. Frontend dispara `window.confirm("¿Cancelar tu orden de compra de 5 AAPL? Se restaurarán USD 1,025.50 a tu saldo.")`. Texto adaptado dinámicamente al `side`, `quantity`, `ticker`, `quotedTotal`.
5. Usuario confirma. Frontend ejecuta `useCancelOrder.mutate(orderId)`.

#### Paso 2: Validación + acción al broker

6. Frontend envía `POST /api/v1/orders/{id}/cancel` con `Authorization: Bearer <jwt>`. Sin body.
7. `JwtAuthenticationFilter` valida JWT. `OrderController.cancelOrder(orderId, principal)` resuelve `userId` desde `principal.userId()` (convención HU-F16 D17).
8. `TradingService.cancelOrder(userId, orderId)` ejecuta:
   - Adquiere lock pessimistic sobre `app.user_balances` (orden canónico balances → positions, D17 F10).
   - Lee `app.orders` con `findByIdAndUserId(orderId, userId)`:
     - Si `Optional.empty()` → `OrderNotFoundException` → 404 `ORDER_NOT_FOUND`. **No se distingue "no existe" de "es de otro usuario"** (defensa anti-enumeración).
     - Si `status != PENDING` → branch por estado:
       - `CANCELED` → short-circuit: emit `ORDER_DUPLICATE_CANCEL_REQUEST` (audit info-only) + retorna 200 con body de la orden + `alreadyCanceledAt: <canceled_at>`.
       - `EXECUTED / REJECTED / FAILED / EXPIRED` → `OrderNotCancelableException(currentStatus)` → 409 `ORDER_NOT_CANCELABLE`.
     - Si `status = PENDING` pero `alpaca_order_id IS NULL` → `OrderNotCancelableException("NO_ALPACA_ID")` → 409 con detalle (defensa: no debería ocurrir si la orden llegó a PENDING vía D29 F09 — toda orden persisted-PENDING llegó via Alpaca accepted).
     - Si `status = PENDING` y `cancel_requested_at IS NOT NULL` → ver §5.2.1 (re-request sobre cancel previamente en polling-timeout).
   - Llega aquí: orden válida, `status=PENDING`, `alpaca_order_id` poblado, `cancel_requested_at IS NULL`.
9. Emite audit `ORDER_CANCEL_REQUESTED` con `details: { orderId, alpacaOrderId, side, ticker, quantity, quotedTotal }`. **Info-only (no rollback si falla).**
10. Invoca `AlpacaTradingAdapter.cancelOrder(alpacaOrderId)`:
    - `DELETE /v2/orders/{alpacaOrderId}` envuelto en `@Retry(name="alpacaApi")` (3 intentos a 1s/3s/5s).
    - Alpaca responde 204 No Content (cancel aceptado).
    - **Polling**: cada 200ms hace `GET /v2/orders/{alpacaOrderId}`, hasta 10 intentos (2s total).
      - Si `status=canceled` → retorna `CancelOutcome.CANCELED(alpacaCanceledAt)`.
      - Si `status=filled` (race: Alpaca llenó justo antes del cancel) → retorna `CancelOutcome.RACE_FILLED(filledAvgPrice, filledQty)`.
      - Si timeout (no canceled ni filled en 2s) → retorna `CancelOutcome.PENDING_CANCEL`.

#### Paso 3: Polling-OK → reverso transaccional

11. `CancelOutcome.CANCELED` recibido. `TradingService.cancelOrder` continúa en la misma transacción:
    - Marca `order.status = CANCELED`, `order.canceled_at = NOW()`, `order.alpaca_canceled_at = <de Alpaca>`.
    - **Reverso BUY**: `portfolioService.credit(userId, order.quoted_total)` — UPDATE `app.user_balances.balance = balance + quoted_total`. El lock pessimistic ya está tomado.
    - Publica `OrderCanceledEvent(orderId, userId, side, ticker, quantity, quotedTotal, canceledAt)` para post-commit listener.
    - COMMIT.

#### Paso 4: Post-commit — notificación + audit

12. `OrderEventListener.handleOrderCanceled` (`@TransactionalEventListener(AFTER_COMMIT)`):
    - Emite audit `ORDER_CANCELED` con `details: { orderId, alpacaOrderId, side: "BUY", ticker, quantity, quotedTotal, refundedAmount: quotedTotal, canceledAt }`.
    - Dispara `notifier.notifyOrderCanceledBuy(emailCommand)` → email "Tu orden de compra de 5 AAPL fue cancelada. Se restauraron USD 1,025.50 a tu saldo".
13. Backend responde `200 OK` con body:
    ```json
    {
      "id": "ord_uuid",
      "clientOrderId": "...",
      "ticker": "AAPL",
      "side": "BUY",
      "type": "MARKET",
      "quantity": 5,
      "quotedUnitPrice": "200.00",
      "quotedTotal": "1025.50",
      "status": "CANCELED",
      "alpacaOrderId": "alp_xxx",
      "submittedAt": "...",
      "canceledAt": "2026-05-26T15:42:25Z",
      "refundedAmount": "1025.50"
    }
    ```
14. Frontend invalida queries de `useBalance` + `usePortfolioPositions` + `useRecentOrders` (`queryClient.invalidateQueries`). UI:
    - Fila desaparece del `PendingOrdersPanel` (filtro `status=PENDING+alpacaOrderId` ya no aplica).
    - `BalanceCard` actualiza con +USD 1,025.50.
    - Toast verde: "✅ Orden cancelada — USD 1,025.50 restaurados a tu saldo".

**Postcondiciones del flujo principal BUY:**

- `app.orders` con `status=CANCELED`, `canceled_at` set.
- `app.user_balances.balance` incrementado por `quoted_total`.
- Email "Orden de compra cancelada" en MailHog.
- Audit `ORDER_CANCEL_REQUESTED` + `ORDER_CANCELED` en ElasticSearch.
- Alpaca paper account refleja la orden como `canceled` (visible en dashboard Alpaca).

### 5.1.b Flujo principal — cancelar orden SELL queued (espejo)

Idéntico estructura, con divergencia en el Paso 3 (reverso):

- **Reverso SELL**: `portfolioService.upsertPosition(userId, ticker, +quantity, avg_buy_price del Order)`:
  - Si fila en `app.positions` existe → `UPDATE quantity = quantity + sellQuantity`.
  - Si fila fue eliminada por el SELL optimista (caso liquidación total) → `INSERT` con `quantity=sellQuantity` y `avg_buy_price` recuperado del `Order.quoted_unit_price` (o un campo dedicado preservado para este caso — ver §7.2). **Decisión D-RESTORE-AVG-BUY-PRICE en plan.md.**
- `OrderCanceledEvent(... side=SELL, restoredQty: quantity ...)`.
- Email "Tu orden de venta de 5 AAPL fue cancelada. Se restauraron 5 acciones a tu posición".

### 5.2 Flujos alternativos

#### 5.2.1 Polling-timeout — `CancelOutcome.PENDING_CANCEL`

**Cuándo se activa:** Alpaca aceptó el `DELETE` pero no confirmó `canceled` en el polling de 2s. Caso común en Alpaca paper bajo carga.

**Comportamiento (continúa desde §5.1 paso 10):**

11'. `CancelOutcome.PENDING_CANCEL` recibido. `TradingService.cancelOrder`:
   - **NO** modifica `status` (sigue `PENDING`).
   - **NO** ejecuta refund/restore.
   - Marca `order.cancel_requested_at = NOW()` y persiste.
   - Publica `OrderCancelPendingEvent(orderId, userId, side, ticker, ...)` para post-commit.
   - COMMIT.

12'. Post-commit:
   - Emite audit `ORDER_CANCEL_REQUESTED` con `details: { ..., outcome: "PENDING_CANCEL" }` (mismo event type que happy-path, distintos details).
   - **NO se envía email** todavía — el usuario verá el feedback visual en UI; el email se dispara cuando reconcile lazy v2 materialice el CANCELED.

13'. Backend responde `200 OK` (no 202 — preferimos consistency, ver D-RESPONSE-CODE-TIMEOUT plan.md) con body:
   ```json
   {
     "id": "ord_uuid",
     "status": "PENDING",
     "cancelRequestedAt": "2026-05-26T15:42:25Z",
     "_message": "Cancelación solicitada. Se completará en los próximos segundos."
   }
   ```

14'. Frontend (`useCancelOrder.onSuccess`):
   - Detecta `cancelRequestedAt && status === 'PENDING'`.
   - Invalida queries (incluyendo `useBalance` → no cambia aún, pero refresca por seguridad).
   - UI: la fila en `PendingOrdersPanel` re-renderea con:
     - `opacity-60` (heredado P1-2 audit Día 10).
     - Botón "Cancelar" disabled.
     - Label "Cancelando…" + spinner `<Loader2 className="animate-spin">`.
     - `aria-busy="true"`.
   - Toast info (no éxito todavía): "🕓 Cancelación en proceso. Verificaremos en unos segundos."

15'. **Reconcile lazy materializa** (siguiente GET `/portfolio/positions` o `/orders` con polling 30s):
   - `OrderReconciliationService.reconcile(...)` v2 detecta orden con `cancel_requested_at IS NOT NULL` + Alpaca `GET /v2/orders/{id}` retorna `status=canceled`.
   - Aplica el reverso (igual que §5.1 paso 11): `status=CANCELED`, `canceled_at=NOW()`, `credit / upsertPosition`, audit `ORDER_CANCELED`, email post-commit.
   - El siguiente refetch del frontend (30s polling o `invalidate` por user interaction) muestra fila desaparecida + balance actualizado.

**Race condition tolerada:** si el usuario hace POST `/cancel` mientras `cancel_requested_at IS NOT NULL` (re-request sobre orden ya en polling-timeout): backend devuelve `200` con el mismo `cancelRequestedAt` existente (idempotencia §5.2.3) — **NO** ejecuta segundo `DELETE` a Alpaca.

#### 5.2.2 Race condition — Alpaca llenó la orden justo antes del cancel

**Cuándo se activa:** `CancelOutcome.RACE_FILLED` retornado por el polling — Alpaca confirmó `status=filled` con `filled_avg_price`/`filled_qty` mientras esperábamos el `canceled`. Posible si la orden estaba PENDING porque Alpaca pre-mercado la encoló y se llenó al abrir el mercado entre nuestro `DELETE` y el siguiente polling.

**Comportamiento:**

- `TradingService.cancelOrder` trata como **fill late-arrival**, no como cancelación:
  - Aplica la lógica de fill: `status=EXECUTED`, `execution_unit_price`, `execution_total`, `executed_at=NOW()`.
  - **BUY**: ya el balance estaba debitado por `quoted_total` (D29 F09); ajustar al `execution_total` real (delta = `execution_total - quoted_total`; si > 0 cobrar extra; si < 0 reembolsar la diferencia). **NO se ejecuta refund completo.**
  - **SELL**: balance NO se había acreditado (D29 F09 SELL queued no acredita); ahora se acredita el `execution_total` real. La posición ya estaba decrementada — NO se restaura.
  - Publica `OrderExecutedEvent` (no `OrderCanceledEvent`) post-commit. Email "Tu orden se ejecutó" (no "Tu orden fue cancelada").
- Backend responde `200` con body de orden EXECUTED + flag `_message: "Tu orden se ejecutó antes de que llegara la cancelación."`.
- Frontend muestra toast informativo distinto: "Tu orden se ejecutó antes de que llegara la cancelación. La cancelación no fue aplicada."

> **Trade-off documentado D-RACE-FILLED-UX en plan.md**: el usuario solicitó cancelar y obtuvo ejecución. Es contra-intuitivo pero es la realidad del broker. Alternativa rechazada: revertir el fill con un counter-order — agrega comisión doble y complejidad por edge case rarísimo en paper trading.

#### 5.2.3 Idempotencia — segunda cancelación sobre orden ya `CANCELED`

**Cuándo se activa:** POST `/cancel` x2 (doble-click del usuario, retry del frontend) sobre orden que ya pasó a `CANCELED`.

**Comportamiento:**

- `findByIdAndUserId` retorna order con `status=CANCELED`.
- `TradingService.cancelOrder` short-circuit: emit audit `ORDER_DUPLICATE_CANCEL_REQUEST` (info-only) + retorna immediately el `OrderResponse` actual.
- HTTP `200 OK` (no 409 — la segunda request "encontró el estado esperado, no hizo nada nuevo"). Body incluye flag `alreadyCanceledAt`.
- **NO se envía segundo email**, NO se llama a Alpaca, NO se ejecuta segundo refund.

#### 5.2.4 Idempotencia — segunda cancelación sobre orden en polling-timeout (`cancel_requested_at IS NOT NULL`)

**Cuándo se activa:** primer POST `/cancel` quedó en polling-timeout; usuario re-cliquea o frontend hace retry antes del próximo reconcile.

**Comportamiento:**

- `findByIdAndUserId` retorna order con `status=PENDING` + `cancel_requested_at` set.
- `TradingService.cancelOrder` short-circuit: emit audit `ORDER_DUPLICATE_CANCEL_REQUEST` + retorna immediately el `OrderResponse` con el `cancelRequestedAt` existente.
- **NO se envía segundo `DELETE` a Alpaca** (defensa anti-doble-cancel).
- Frontend recibe respuesta consistente con la primera (mismo `cancelRequestedAt`, mismo UI state).

### 5.3 Flujos de error

#### 5.3.1 Orden no existe (o es de otro usuario)

- `findByIdAndUserId` retorna `Optional.empty()`.
- `OrderNotFoundException` → HTTP 404 + `ORDER_NOT_FOUND`. Body: `{ error: "ORDER_NOT_FOUND", traceId, message: "No se encontró la orden solicitada." }`.
- **NO se distingue "no existe" de "es de otro usuario"** (defensa anti-enumeración — un atacante no debe poder probar UUIDs para identificar órdenes ajenas).
- Audit: **NO se emite** (404 es nivel HTTP, no business event). El JWT filter ya emitió `LOGIN_ATTEMPT/ALLOWED` upstream.

#### 5.3.2 Orden no cancelable (`EXECUTED / REJECTED / FAILED / EXPIRED`)

- `findByIdAndUserId` retorna order con `status != PENDING`.
- `OrderNotCancelableException(currentStatus)` → HTTP 409 + `ORDER_NOT_CANCELABLE`. Body: `{ error: "ORDER_NOT_CANCELABLE", details: { currentStatus: "EXECUTED" }, message: "La orden ya fue ejecutada y no puede cancelarse." }` (mensaje varía según `currentStatus`).
- Audit `ORDER_CANCEL_REJECTED` con `details: { orderId, currentStatus, reason: "NOT_CANCELABLE" }`.

#### 5.3.3 Alpaca caída (post-retries)

- `AlpacaTradingAdapter.cancelOrder` lanza `AlpacaApiException` tras 3 retries (1s/3s/5s).
- `TradingService.cancelOrder` propaga.
- `GlobalExceptionHandler` → HTTP 502 + `BROKER_UNAVAILABLE`. Body: `{ error: "BROKER_UNAVAILABLE", traceId, message: "El broker no respondió. Intenta nuevamente en unos minutos." }`.
- **Estado final BD:** orden sigue `PENDING`, `cancel_requested_at IS NULL` (no marcamos request porque no salió). NO refund, NO email.
- Audit `ORDER_CANCEL_FAILED` con `details: { orderId, reason: "BROKER_UNAVAILABLE" }`.
- Frontend: toast rojo "El broker no respondió. Tu orden sigue en cola. Intenta de nuevo en unos minutos."

#### 5.3.4 Alpaca rechaza el `DELETE` (404 / 422)

**Cuándo se activa:** Alpaca responde 404 (el `alpacaOrderId` ya no existe — orden ya canceled/filled en el lado del broker pero BD local sigue PENDING) o 422 (orden no cancelable según Alpaca, ej. ya filled).

**Comportamiento:**

- `AlpacaTradingAdapter.cancelOrder` distingue el `404` y lanza `AlpacaOrderNotFoundException`. Para `422` lanza `AlpacaOrderNotCancelableException`.
- `TradingService.cancelOrder` los trata como **señal de drift** entre BD local y Alpaca:
  - **Dispara reconcile lazy v2 inline** (mismo tx): `getOrder(alpacaOrderId)` para obtener el estado real desde Alpaca.
  - Si Alpaca dice `canceled` → aplica transición `CANCELED` + refund/restore. Audit `ORDER_CANCELED` con `details: { drift: true }`.
  - Si Alpaca dice `filled` → aplica transición `EXECUTED` (§5.2.2 RACE_FILLED).
  - Si Alpaca dice `expired` → aplica transición `EXPIRED` (§5.2.5).
- Backend responde 200 con el estado materializado. Frontend muestra toast informativo según el outcome real.

> **Decisión:** este "drift detected at cancel time" es valioso — el cancel-request del usuario lo detecta y lo cierra inmediato, sin esperar al próximo GET. Reusa el código path del reconcile lazy v2.

#### 5.3.5 Reconcile lazy v2 detecta `expired` desde Alpaca

**Cuándo se activa:** transición outbound del broker — TIF day de la orden expiró sin fill. La orden estaba `PENDING + alpacaOrderId` en BD; el siguiente reconcile (próximo GET) lee Alpaca con `status=expired`.

**Comportamiento:**

- `OrderReconciliationService.reconcileOrder(order)` v2:
  - Lee Alpaca `GET /v2/orders/{alpacaOrderId}` → `status=expired`.
  - **Reverse igual que CANCELED** (porque expired sin fill significa la orden no se ejecutó):
    - **BUY**: refund de `quoted_total` al balance.
    - **SELL**: restore de `quantity` a la posición.
  - Marca `order.status = EXPIRED` (no CANCELED — preservamos la causa real).
  - Publica `OrderExpiredEvent` post-commit.
- Audit `ORDER_EXPIRED` con `details: { orderId, reason: "TIF_DAY_EXPIRED", refundedAmount/restoredQty }`.
- Email "Tu orden de compra/venta de N AAPL expiró sin ejecutarse. Se restauraron tus fondos / acciones." — templates Thymeleaf reusan `order-canceled-*.html` con la sola diferencia del título (decisión D-EMAIL-EXPIRED-REUSE plan.md: reuso simple con flag).

#### 5.3.6 Reconcile lazy v2 detecta `rejected` desde Alpaca

**Cuándo se activa:** Alpaca rechaza una orden previamente accepted (raro pero documentado por Alpaca: "compliance review", "market closed unexpectedly", etc.).

**Comportamiento:**

- Igual mecánica que §5.3.5 pero `order.status = REJECTED`, audit `ORDER_REJECTED` (event type ya existente F09).
- Reverse aplicado (refund BUY / restore SELL).
- Email reusa `order-rejected-*.html` (templates ya existen F09/F10).

#### 5.3.7 Usuario no autenticado / cuenta no activa

- Heredado F09/F10: 401 `AUTHENTICATION_REQUIRED` (heredado mini-HU token-rotation Día 10 checkpoint 1, deuda #1 cerrada) o 403 `ACCOUNT_NOT_ACTIVE`.

#### 5.3.8 Concurrencia — dos cancelaciones simultáneas del mismo `orderId`

**Cuándo se dispara:** doble-click muy rápido (sub-200ms) o frontend con `useMutation` sin deduplicación, lanzando dos POST `/cancel` simultáneos sobre la misma orden.

**Comportamiento:**

- El lock pessimistic sobre `app.user_balances` serializa las dos. La primera procesa normalmente (CANCELED + refund). La segunda al adquirir el lock encuentra `status=CANCELED` → short-circuit §5.2.3 (200 con `alreadyCanceledAt`).
- **Importante:** el `DELETE` a Alpaca se ejecuta UNA vez (la primera). La segunda short-circuit antes del adapter call.

#### 5.3.9 Concurrencia — cancel solicitado mientras reconcile lazy materializa el EXECUTED

**Edge muy específico:** usuario abre `/portfolio` (dispara reconcile lazy), reconcile detecta que Alpaca llenó la orden y la marca EXECUTED. **Mientras tanto**, en otra pestaña el usuario hace click en "Cancelar" sobre la fila del PendingOrdersPanel que aún no ha refreshed.

**Comportamiento:**

- POST `/cancel` llega después del COMMIT del reconcile.
- `findByIdAndUserId` retorna order con `status=EXECUTED`.
- §5.3.2 aplica: 409 `ORDER_NOT_CANCELABLE` con `details.currentStatus=EXECUTED`.
- Frontend al recibir 409: invalida `useBalance` + `usePortfolioPositions` + `useRecentOrders`. El próximo render muestra la posición real + saldo real. Toast informativo "La orden ya se había ejecutado".

---

## 6. Contratos de datos

### 6.1 Endpoint nuevo

| Método | Path | Auth | Body | Response 2xx | Errores |
|---|---|---|---|---|---|
| `POST` | `/api/v1/orders/{id}/cancel` | JWT (rol INVESTOR + estado ACTIVE) | Vacío | 200 OK con `OrderResponse` extendido | 404, 409, 502 |

### 6.2 `OrderResponse` (extensión retro-compatible)

```yaml
components:
  schemas:
    OrderResponse:
      # ... campos heredados F09/F10 ...
      properties:
        status:
          type: string
          enum: [PENDING, EXECUTED, REJECTED, FAILED, CANCELED, EXPIRED]  # 2 valores NUEVOS en F15
        canceledAt:
          type: string
          format: date-time
          nullable: true
          description: |
            Timestamp en que la orden quedó CANCELED. NULL si status != CANCELED.
        cancelRequestedAt:
          type: string
          format: date-time
          nullable: true
          description: |
            Timestamp en que el usuario solicitó cancelar y el polling Alpaca dio timeout.
            NULL si la cancelación nunca se solicitó o ya se materializó (CANCELED).
            Coexiste con status=PENDING: la orden sigue PENDING en BD esperando que reconcile lazy v2 la materialice.
        expiredAt:
          type: string
          format: date-time
          nullable: true
          description: |
            Timestamp en que la orden expiró (TIF day expirado). NULL si status != EXPIRED.
        refundedAmount:
          type: string
          nullable: true
          description: |
            Solo poblado en BUY canceladas/expiradas: monto restaurado al balance.
        restoredQty:
          type: integer
          nullable: true
          description: |
            Solo poblado en SELL canceladas/expiradas: cantidad restaurada a la posición.
```

**Retro-compatibilidad:** clientes F09/F10/F16/F17 reciben los 4 campos nuevos pero los ignoran. Sin breaking change.

### 6.3 Códigos de error nuevos

| Código HTTP | Código aplicación | Cuándo | Detalles |
|---|---|---|---|
| 404 | `ORDER_NOT_FOUND` | Order no existe o pertenece a otro usuario | (vacío, defensa anti-enumeración) |
| 409 | `ORDER_NOT_CANCELABLE` | Order existe pero `status != PENDING` o falta `alpacaOrderId` | `{ currentStatus: "EXECUTED" / "REJECTED" / ... }` |
| 502 | `BROKER_UNAVAILABLE` | Alpaca down post-retries | `{ retryAfter: 60 }` |

Códigos heredados que pueden emitirse: `401 AUTHENTICATION_REQUIRED`, `403 ACCOUNT_NOT_ACTIVE`.

---

## 7. Cambios en base de datos

### 7.1 Migración a crear: `V6__add_canceled_expired_status_and_cancel_columns.sql`

> **Nota**: V6 (no V8 como anticipaba un draft anterior) — el último Flyway aplicado es V5 (F09). Decisión emergente D25 en plan.md §2.4.

```sql
-- V6: Cancelación de órdenes (HU-F15)
-- Agrega 2 valores al enum OrderStatus + 3 columnas a app.orders
-- Idempotente: la primera vez aplica; corridas posteriores son no-op (DROP IF EXISTS + ADD)

ALTER TABLE app.orders DROP CONSTRAINT IF EXISTS chk_order_status;
ALTER TABLE app.orders ADD CONSTRAINT chk_order_status
  CHECK (status IN ('PENDING', 'EXECUTED', 'REJECTED', 'FAILED', 'CANCELED', 'EXPIRED'));

ALTER TABLE app.orders ADD COLUMN IF NOT EXISTS cancel_requested_at TIMESTAMPTZ NULL;
ALTER TABLE app.orders ADD COLUMN IF NOT EXISTS canceled_at TIMESTAMPTZ NULL;
ALTER TABLE app.orders ADD COLUMN IF NOT EXISTS expired_at TIMESTAMPTZ NULL;

-- Índice parcial para acelerar reconcile lazy v2 (busca órdenes con cancel solicitado pendiente)
CREATE INDEX IF NOT EXISTS idx_orders_cancel_requested_at
  ON app.orders (cancel_requested_at)
  WHERE cancel_requested_at IS NOT NULL AND status = 'PENDING';
```

### 7.2 Modificaciones a entidades JPA

**`Order.java`:**

- Enum `OrderStatus` agrega: `CANCELED`, `EXPIRED`.
- Campos nuevos:
  - `Instant cancelRequestedAt;` (nullable, `@Column(name="cancel_requested_at")`)
  - `Instant canceledAt;` (nullable)
  - `Instant expiredAt;` (nullable)
- Métodos de dominio nuevos:
  - `markCancelRequested()` — set `cancelRequestedAt = Instant.now()`.
  - `markCanceled(Instant alpacaCanceledAt)` — set `status=CANCELED`, `canceledAt=NOW()`, `alpaca_canceled_at=alpacaCanceledAt` (campo ya existe en V5 de F09, ojo confirmar).
  - `markExpired()` — set `status=EXPIRED`, `expiredAt=NOW()`.
  - `isCancelable()` — boolean: true si `status=PENDING && alpaca_order_id != null`.

> **Verificación previa al Lote A:** correr `psql -d bloomtrade -c "\d app.orders"` y confirmar (a) `chk_order_status` actual lista los 4 valores `(PENDING, EXECUTED, REJECTED, FAILED)`; (b) existencia o no de `alpaca_canceled_at` (F09 V5). **Confirmado pre-coding Lote A: NO existe `alpaca_canceled_at` en V5.** Decisión D26 en plan.md §2.4: NO agregar el campo en V6 — Alpaca devuelve el timestamp en el body del polling response y se mapea a `canceled_at`. La columna queda fuera de scope.

### 7.3 Datos semilla

**Ninguno.** F15 no requiere config.

---

## 8. Mapeo arquitectónico

### 8.1 Módulos involucrados

| Módulo | Rol en F15 | Cambios concretos |
|---|---|---|
| TradingService | Iniciador + orquestador | Agrega `cancelOrder(userId, orderId)` con dispatch BUY/SELL para el reverse. 2 excepciones nuevas. |
| PortfolioService | Notificado (escritura) | **Sin métodos nuevos**: reutiliza `credit(userId, amount)` (F10) y `upsertPosition(userId, ticker, qty, avgBuyPrice)` (F09). Verificar que `upsertPosition` maneje el caso re-INSERT cuando fila fue eliminada. |
| IntegrationService | Intermediario | Agrega `AlpacaTradingAdapter.cancelOrder(alpacaOrderId)` con polling 2s. 2 excepciones nuevas (`AlpacaOrderNotFoundException`, `AlpacaOrderNotCancelableException`). |
| OrderReconciliationService | Reconciliador lazy | **v2** extiende v1: maneja transiciones outbound `canceled`, `rejected`, `expired` de Alpaca. Ejecuta reverse cuando aplica. Reutiliza `TradingService.applyCancelTransition / applyExpireTransition` (helpers internos). |
| NotificationService | Notificado (despacho async) | `Notifier` +2 métodos: `notifyOrderCanceledBuy`, `notifyOrderCanceledSell`. Para EXPIRED: reutiliza los mismos templates con flag (decisión D-EMAIL-EXPIRED-REUSE plan.md). |
| AuditService | Notificado (registro) | 3 event types nuevos: `ORDER_CANCEL_REQUESTED`, `ORDER_CANCELED`, `ORDER_EXPIRED`. `ORDER_REJECTED` (existente) recibe nuevo trigger desde reconcile v2. `ORDER_DUPLICATE_CANCEL_REQUEST` para idempotencia. `ORDER_CANCEL_REJECTED` para 409. `ORDER_CANCEL_FAILED` para 502. **Total: 6 event types nuevos.** |
| AdminService | Configuración | Sin cambios. |

### 8.2 Interfaces expuestas

| Interfaz | Quién la consumirá | Contrato nuevo o modificado |
|---|---|---|
| `TradingService.cancelOrder(userId, orderId)` | `OrderController` | **Nuevo.** Retorna `OrderResponse`. Lanza `OrderNotFoundException`, `OrderNotCancelableException`, `AlpacaApiException`. |
| `AlpacaTradingAdapter.cancelOrder(alpacaOrderId)` | `TradingService` | **Nuevo.** Retorna `CancelOutcome` (sealed: `CANCELED`, `PENDING_CANCEL`, `RACE_FILLED`). |
| `OrderRepository.findByIdAndUserId(orderId, userId)` | `TradingService` | **Nuevo.** `Optional<Order>`. Defensa anti-enumeración. |
| `Notifier.notifyOrderCanceledBuy(emailCommand)` | `OrderEventListener` | **Nuevo.** |
| `Notifier.notifyOrderCanceledSell(emailCommand)` | `OrderEventListener` | **Nuevo.** |
| `OrderReconciliationService.reconcileOrder(order)` | Caller `PortfolioService.getPositions` etc. | **Extendido v2.** Internalmente maneja outcomes canceled/rejected/expired. Firma sin cambio. |

### 8.3 Tácticas de Bass aplicadas

- **TAC-I1 (Orchestrator)** — `TradingService.cancelOrder` orquesta validación → adapter → reverse → audit → email.
- **TAC-D1 (Idempotency)** — implícita por `order.id` + status check (§5.2.3, §5.2.4).
- **TAC-S5 (Validar entradas)** — `findByIdAndUserId` defensa anti-enumeración; `isCancelable()` check de estado.
- **TAC-M1 (Adapter)** — `AlpacaTradingAdapter.cancelOrder` aisla el detalle de polling.
- **TAC-D2 (Retry)** — `@Retry(name="alpacaApi")` heredado en el adapter.

---

## 9. Efectos colaterales

### 9.1 Eventos de auditoría

**6 event types nuevos en F15:**

| `event_type` | Trigger | `details` |
|---|---|---|
| `ORDER_CANCEL_REQUESTED` | POST `/cancel` válido + DELETE enviado a Alpaca | `{ orderId, alpacaOrderId, side, ticker, quantity, quotedTotal, outcome: "CANCELED"/"PENDING_CANCEL" }` |
| `ORDER_CANCELED` | Transición `PENDING → CANCELED` (polling-OK, reconcile lazy v2, drift detected) | `{ orderId, alpacaOrderId, side, ticker, quantity, refundedAmount/restoredQty, canceledAt, source: "USER_REQUEST"/"BROKER_CANCEL"/"DRIFT_RECONCILE" }` |
| `ORDER_EXPIRED` | Transición `PENDING → EXPIRED` (reconcile lazy v2) | `{ orderId, alpacaOrderId, side, ticker, quantity, refundedAmount/restoredQty, expiredAt }` |
| `ORDER_DUPLICATE_CANCEL_REQUEST` | Idempotency hit (re-cancel sobre CANCELED o cancel_requested_at set) | `{ orderId, existingStatus, alreadyCanceledAt/cancelRequestedAt }` |
| `ORDER_CANCEL_REJECTED` | 409 ORDER_NOT_CANCELABLE | `{ orderId, currentStatus, reason: "NOT_CANCELABLE" }` |
| `ORDER_CANCEL_FAILED` | 502 BROKER_UNAVAILABLE | `{ orderId, reason: "BROKER_UNAVAILABLE", retryAfter }` |

**`ORDER_REJECTED` (existente F09)** se reutiliza cuando reconcile lazy v2 detecta Alpaca rejected (§5.3.6).

### 9.2 Notificaciones

| Trigger | Plantilla | Contenido resumido |
|---|---|---|
| `ORDER_CANCELED` con `side=BUY` (user-requested o broker-canceled) | `order-canceled-buy.html` (nueva) | "Tu orden de compra de {quantity} {ticker} fue cancelada. Se restauraron USD {refundedAmount} a tu saldo. Saldo actual: USD {newBalance}." |
| `ORDER_CANCELED` con `side=SELL` | `order-canceled-sell.html` (nueva) | "Tu orden de venta de {quantity} {ticker} fue cancelada. Se restauraron {restoredQty} {ticker} a tu posición." |
| `ORDER_EXPIRED` con `side=BUY` | `order-canceled-buy.html` (reuso, flag `isExpired=true` en context) | "Tu orden de compra de {quantity} {ticker} expiró sin ejecutarse. Se restauraron USD {refundedAmount} a tu saldo." |
| `ORDER_EXPIRED` con `side=SELL` | `order-canceled-sell.html` (reuso) | Análogo. |
| `ORDER_REJECTED` con `side=*` (via reconcile v2) | Reutiliza `order-rejected-{buy,sell}.html` (F09/F10) | Sin cambio. |

> **Decisión D-EMAIL-EXPIRED-REUSE plan.md**: reuso de templates `order-canceled-*` para EXPIRED con flag de contexto, en lugar de crear 2 templates `order-expired-*`. Razón: el copy difiere en una sola palabra ("fue cancelada" vs "expiró sin ejecutarse"); duplicar templates es overkill.

**NO se envía email para:** `ORDER_CANCEL_REQUESTED` (info-only para audit), `ORDER_DUPLICATE_CANCEL_REQUEST`, `ORDER_CANCEL_REJECTED`, `ORDER_CANCEL_FAILED`. El usuario ya ve el feedback en UI.

### 9.3 Cambios en caché Redis

**No aplica.** F15 no introduce nuevos cache keys. Reutiliza:

- `market-data:price:{ticker}` (F18) — no usado en cancel (no necesitamos precio actual para cancelar).
- Invalidación al frontend: `queryClient.invalidateQueries(['balance', 'positions', 'recentOrders'])` post-cancel.

### 9.4 Llamadas a APIs externas

| API | Método | Adapter | Cuándo |
|---|---|---|---|
| Alpaca Markets (paper) | `DELETE /v2/orders/{alpacaOrderId}` | `AlpacaTradingAdapter.cancelOrder` | Cada `POST /cancel` exitoso post-validaciones. |
| Alpaca Markets (paper) | `GET /v2/orders/{alpacaOrderId}` (polling 200ms × 10) | `AlpacaTradingAdapter.cancelOrder` | Polling inmediato post-DELETE. Reusa el `GET /orders/{id}` heredado F09 (mismo client REST). |
| Alpaca Markets (paper) | `GET /v2/orders/{alpacaOrderId}` (reconcile) | `OrderReconciliationService` v2 | Cada GET `/portfolio` o `/orders` que detecta orden con `cancel_requested_at` set + tiempo > umbral. |

**RetryPolicy:** heredada `alpacaApi` (3×1s/3s/5s). El polling DE cancel (2s × 10) es **dentro** del adapter — NO se cuenta como retries de la retry policy (es un loop interno).

---

## 10. Atributos de calidad aplicables

### 10.1 Escenarios de calidad referenciados

- **ESC-D1 (Disponibilidad)** — caída de Alpaca durante NYSE: F15 propaga 502 + estado intacto BD.
- **ESC-S3 (Seguridad)** — cancelación de orden ajena: 404 anti-enumeración.
- **ESC-I1 (Capacidad de respuesta)** — `POST /cancel` responde en <5s p95 (polling cap 2s + overhead BD <1s).

### 10.2 Constraints específicos de esta feature

| Constraint | Medida | Cómo se verifica |
|---|---|---|
| `POST /cancel` polling-OK responde en <3s p95 | 20 cancelaciones BUY queued | Test IT con WireMock fast-canceled |
| `POST /cancel` polling-timeout responde en ≤2.5s | Mock WireMock con delay 3s en GET | Test IT |
| BigDecimal usado en refund (sin double/float) | Grep | `TradingService.cancelOrder` + `PortfolioService.credit` |
| Lock canónico `balances → positions` consistente con D17 F10 | Test IT concurrencia | Cancel BUY + Cancel SELL simultáneos sin deadlock |
| `app.user_balances.balance` solo INCREMENTA en cancel-BUY-refund | Test IT | Assert: balance final > balance inicial |
| `app.positions.quantity` INCREMENTA en cancel-SELL-restore (puede re-INSERT) | Test IT | Caso liquidación total (fila eliminada) → cancel re-INSERT con `avg_buy_price` |
| Idempotencia por `order.id` cubre doble-cancel | Test IT N=10 paralelos | 1 cancel real, 9 short-circuit 200 |
| `findByIdAndUserId` defensa anti-enumeración | Test IT cross-user | User B cancela orden de User A → 404 (no 403) |
| Reconcile lazy v2 cubre `canceled / rejected / expired` desde Alpaca | Test IT con WireMock simulating outbound transitions | 3 escenarios separados |
| `ORDER_NOT_CANCELABLE` se valida antes de llamar a Alpaca | Test IT | Mock WireMock: `verify(0, deleteRequestedFor(...))` |
| Race condition RACE_FILLED se trata como EXECUTED no CANCELED | Test IT | Mock polling devuelve `filled` en intento #3 → assert status EXECUTED |

---

## 11. Criterios de aceptación

### 11.1 Escenarios de aceptación

```gherkin
Funcionalidad: Cancelar orden Market

  Antecedentes:
    Dado un usuario autenticado "juan@example.com" con saldo USD 7000.00 y rol INVESTOR
    Y Alpaca responde a DELETE /v2/orders/{id} con 204 No Content (cancel accepted)
    Y Alpaca responde a GET /v2/orders/{id} con status="canceled" en el segundo polling

  Escenario: Cancelar orden BUY queued exitosa (polling-OK)
    Dado que existe orden { id: "ord_1", side: "BUY", ticker: "AAPL", quantity: 5, quoted_total: "1025.50", status: "PENDING", alpaca_order_id: "alp_a" }
    Y app.user_balances.balance está en 7000.00 (debitado por D29 F09)
    Cuando el usuario envía POST /api/v1/orders/ord_1/cancel
    Entonces el sistema responde 200 con status="CANCELED" y refundedAmount="1025.50"
    Y app.orders tiene fila ord_1 con status=CANCELED y canceled_at poblado
    Y app.user_balances.balance pasa de 7000.00 a 8025.50
    Y se emiten ORDER_CANCEL_REQUESTED y ORDER_CANCELED a ElasticSearch
    Y MailHog recibe email "Tu orden de compra de 5 AAPL fue cancelada"

  Escenario: Cancelar orden SELL queued exitosa (polling-OK con restore re-INSERT)
    Dado que existe orden { id: "ord_2", side: "SELL", ticker: "AAPL", quantity: 5, quoted_unit_price: "190.00", status: "PENDING", alpaca_order_id: "alp_b" }
    Y app.positions NO tiene fila para (userId, AAPL) (fue eliminada por el SELL optimista)
    Cuando el usuario envía POST /api/v1/orders/ord_2/cancel
    Entonces el sistema responde 200 con status="CANCELED" y restoredQty=5
    Y app.positions tiene fila { userId, AAPL, quantity: 5, avg_buy_price: 190.00 }   # re-INSERT
    Y app.user_balances.balance NO se modifica (la venta nunca se acreditó D-SELL-QUEUED-RISK F10)
    Y MailHog recibe email "Tu orden de venta de 5 AAPL fue cancelada. Se restauraron 5 acciones."

  Escenario: Polling-timeout deja la orden en cancel_requested_at
    Dado que existe orden ord_1 PENDING como arriba
    Y Alpaca responde a DELETE con 204 pero NO responde "canceled" en GET durante 2s
    Cuando el usuario envía POST /orders/ord_1/cancel
    Entonces el sistema responde 200 con status="PENDING" y cancelRequestedAt poblado
    Y app.orders tiene ord_1 con cancel_requested_at poblado, status sigue PENDING
    Y app.user_balances.balance NO se modifica todavía
    Y NO se envía email todavía
    Y se emite ORDER_CANCEL_REQUESTED con outcome="PENDING_CANCEL"

  Escenario: Reconcile lazy v2 materializa cancel pendiente
    Dado que ord_1 quedó en PENDING + cancel_requested_at del escenario anterior
    Y Alpaca ahora responde GET /orders/alp_a con status="canceled"
    Cuando el usuario hace GET /api/v1/portfolio/positions
    Entonces OrderReconciliationService v2 detecta la transición
    Y app.orders.ord_1 pasa a status=CANCELED + canceled_at poblado
    Y app.user_balances.balance se refunda por quoted_total
    Y se emite ORDER_CANCELED a ElasticSearch
    Y MailHog recibe email "Tu orden de compra fue cancelada"

  Escenario: Reconcile lazy v2 detecta expired desde Alpaca
    Dado que existe orden ord_3 BUY PENDING + alpaca_order_id="alp_c"
    Y Alpaca responde GET /orders/alp_c con status="expired" (TIF day expirado)
    Cuando el usuario hace GET /portfolio/positions
    Entonces app.orders.ord_3 pasa a status=EXPIRED + expired_at poblado
    Y el balance se refunda
    Y se emite ORDER_EXPIRED a auditoría
    Y email "Tu orden expiró sin ejecutarse"

  Escenario: Cancelación con clientOrderId duplicado responde idempotente
    Dado que ord_1 ya pasó a CANCELED en t0
    Cuando el usuario envía POST /orders/ord_1/cancel a t0+100ms (doble-click)
    Entonces el sistema responde 200 (no 409) con status="CANCELED" y alreadyCanceledAt=t0
    Y NO se llama a Alpaca DELETE
    Y NO se ejecuta segundo refund
    Y se emite ORDER_DUPLICATE_CANCEL_REQUEST

  Escenario: Cancelación sobre orden EXECUTED retorna 409
    Dado que existe orden ord_4 con status="EXECUTED"
    Cuando el usuario envía POST /orders/ord_4/cancel
    Entonces el sistema responde 409 con código ORDER_NOT_CANCELABLE y details.currentStatus="EXECUTED"
    Y se emite ORDER_CANCEL_REJECTED a auditoría
    Y NO se llama a Alpaca

  Escenario: Cancelación cross-user retorna 404 (defensa anti-enumeración)
    Dado que existe orden ord_5 del usuario B en PENDING
    Y el usuario A está autenticado
    Cuando el usuario A envía POST /orders/ord_5/cancel
    Entonces el sistema responde 404 con código ORDER_NOT_FOUND
    Y NO se distingue de "orden no existe"
    Y NO se emite audit ORDER_CANCEL_REJECTED (es 404 HTTP, no business event)
    Y la orden ord_5 sigue intacta

  Escenario: Alpaca down post-retries devuelve 502 sin modificar BD
    Dado que Alpaca responde 503 a DELETE en los 3 retries (1s/3s/5s)
    Y existe orden ord_1 PENDING
    Cuando el usuario envía POST /orders/ord_1/cancel
    Entonces el sistema responde 502 con código BROKER_UNAVAILABLE
    Y app.orders.ord_1 sigue PENDING (sin cancel_requested_at)
    Y app.user_balances NO se modifica
    Y se emite ORDER_CANCEL_FAILED a auditoría

  Escenario: Race condition RACE_FILLED se trata como EXECUTED no CANCELED
    Dado que existe orden ord_1 BUY PENDING
    Y Alpaca responde DELETE 204 pero GET en polling #2 dice status="filled" con filled_avg_price="198.50"
    Cuando el usuario envía POST /orders/ord_1/cancel
    Entonces el sistema responde 200 con status="EXECUTED" (no CANCELED)
    Y app.orders.ord_1 tiene status=EXECUTED, execution_unit_price=198.50
    Y app.user_balances.balance se ajusta por la diferencia execution_total - quoted_total
    Y se emite ORDER_EXECUTED (no ORDER_CANCELED)
    Y MailHog recibe "Tu orden se ejecutó antes de la cancelación"

  Escenario: Concurrencia — dos cancels simultáneos del mismo orderId
    Dado que existe orden ord_1 PENDING
    Cuando se envían POST /orders/ord_1/cancel simultáneamente x2
    Entonces exactamente UNO procesa (CANCELED + refund)
    Y el OTRO short-circuit con 200 + alreadyCanceledAt
    Y app.user_balances refleja UN solo refund (no doble crédito)
    Y Alpaca recibió UN solo DELETE
```

### 11.2 Trazabilidad criterios → escenarios

| Criterio HU-F15 | Escenario Gherkin |
|---|---|
| E1: Cancelar BUY queued con refund inmediato | "Cancelar orden BUY queued exitosa (polling-OK)" |
| E2: Cancelar SELL queued con restore de posición (incluyendo re-INSERT si fue eliminada) | "Cancelar orden SELL queued exitosa..." |
| E3: Polling-timeout deja flag y reconcile materializa | "Polling-timeout deja la orden en cancel_requested_at" + "Reconcile lazy v2 materializa cancel pendiente" |
| E4: Reconcile v2 maneja `expired` outbound | "Reconcile lazy v2 detecta expired" |
| E5: Idempotencia implícita por order.id | "Cancelación con clientOrderId duplicado..." |
| E6: 409 sobre estados no cancelables | "Cancelación sobre orden EXECUTED" |
| E7: Defensa anti-enumeración cross-user | "Cancelación cross-user retorna 404" |
| E8: Alpaca down → 502 sin modificación BD | "Alpaca down post-retries" |
| E9: Race condition RACE_FILLED se respeta | "Race condition RACE_FILLED..." |
| E10: Concurrencia serializada por lock pessimistic | "Concurrencia — dos cancels simultáneos" |

---

## 12. UI y experiencia

### 12.1 Páginas / vistas afectadas

#### Página `/portfolio` (modificada)

**Componente afectado:** `PendingOrdersPanel`.

**Cambios:**

1. Columna nueva "Acciones" al final de cada fila.
2. Botón `<Button variant="ghost" size="sm">Cancelar</Button>` por fila de orden PENDING+alpacaOrderId.
3. Al hacer click: `window.confirm` con texto adaptado (`side=BUY` → "Se restaurarán USD X a tu saldo"; `side=SELL` → "Se restaurarán N acciones a tu posición").
4. Si la fila tiene `cancelRequestedAt` set (polling-timeout o reconcile en progreso): renderear con `opacity-60` + botón disabled + label "Cancelando…" + spinner `<Loader2 className="animate-spin h-4 w-4" />` + `aria-busy="true"`.

#### Página `/dashboard` (modificada)

**Componente afectado:** `RecentOrdersWidget`.

**Cambios:**

1. Para filas con `status === 'PENDING'`: agregar columna "Acciones" con botón "Cancelar" (mismo patrón).
2. Filas con otros estados (`EXECUTED`, `CANCELED`, etc.): NO renderear botón.
3. Misma lógica de visual feedback para `cancelRequestedAt`.

> **Trade-off documentado D-DUPLICATE-CANCEL-UI plan.md**: tener el botón en 2 sitios (PendingOrdersPanel + RecentOrdersWidget) genera duplicación mínima. Pero la decisión Q9 del SDD Paso 1 priorizó conveniencia (usuario en dashboard puede cancelar sin saltar a /portfolio). El handler `useCancelOrder` es uno solo y invalida queries de ambos contextos — mantener consistencia es trivial.

### 12.2 Componentes nuevos a crear

| Componente | Propósito |
|---|---|
| `useCancelOrder` (hook) | `useMutation<OrderResponse, ApiError, string>` que llama `ordersApi.cancelOrder(orderId)`. `onSuccess` invalida queries balance + positions + recentOrders. `onError` muestra toast con el código del error. |
| `CancelOrderButton` (component) | Botón reutilizable con confirm dialog inline. Props: `order: OrderResponse`. Renderea distinto si `order.cancelRequestedAt` está set. |

### 12.3 Componentes modificados

| Componente | Cambios |
|---|---|
| `PendingOrdersPanel.tsx` | Agregar columna "Acciones" + integrar `<CancelOrderButton order={...} />`. |
| `RecentOrdersWidget.tsx` | Condicional `{row.status === 'PENDING' && <CancelOrderButton order={row} />}`. |
| `OrderResponse` type (types/api.ts) | Agregar `canceledAt?: string`, `cancelRequestedAt?: string`, `expiredAt?: string`, `refundedAmount?: string`, `restoredQty?: number`. Extender `OrderStatus` enum tipo: `'PENDING' \| 'EXECUTED' \| 'REJECTED' \| 'FAILED' \| 'CANCELED' \| 'EXPIRED'`. |
| `messages.es.ts` | +`ORDER_NOT_FOUND`, +`ORDER_NOT_CANCELABLE` (con interpolación de `currentStatus`), +`BROKER_UNAVAILABLE`, +`ORDER_CANCEL_SUCCESS`, +`ORDER_CANCEL_PENDING`, +`ORDER_CANCEL_RACE_FILLED`. |
| `ordersApi.ts` | +`cancelOrder(orderId: string): Promise<OrderResponse>`. |

### 12.4 Hooks o utilidades modificados

| Item | Cambios |
|---|---|
| `useRecentOrders` (F17) | Sin cambios estructurales — `OrderResponse` extendido ya cubre. |
| `usePortfolioPositions` (F16) | Sin cambios estructurales. |
| `useBalance` (F21) | Sin cambios estructurales. |

### 12.5 Cambios de routing

**Ninguno.** F15 reusa `/portfolio` y `/dashboard`.

### 12.6 Mensajes de error en `messages.es.ts`

```typescript
// Códigos nuevos en F15:
ORDER_NOT_FOUND: "No se encontró la orden solicitada.",
ORDER_NOT_CANCELABLE: "Tu orden ya está en estado {currentStatus} y no puede cancelarse.",
BROKER_UNAVAILABLE: "El broker no respondió. Tu orden sigue en cola. Intenta de nuevo en unos minutos.",

// Códigos de éxito (consumidos por toast/banner):
ORDER_CANCEL_SUCCESS: "Orden cancelada — {refundDescription}",
  // refundDescription dinámico: "USD X restaurados a tu saldo" o "N acciones restauradas a tu posición"
ORDER_CANCEL_PENDING: "Cancelación en proceso. Verificaremos en unos segundos.",
ORDER_CANCEL_RACE_FILLED: "Tu orden se ejecutó antes de que llegara la cancelación. La cancelación no fue aplicada.",
```

---

## 13. Fuera de alcance de esta spec

- **Cancelación de orden en pleno `placeOrder` (race condition dentro de polling submit)** — deuda post-MVP. Solo se cancela orden ya persisted-PENDING + alpacaOrderId.
- **Cancelación masiva** (cancelar todas las PENDING del usuario en una llamada) — sin caso de uso MVP.
- **Cancelación programada** (cancelar si no se ejecuta en X minutos) — depende de scheduler dedicado, post-MVP.
- **Edición de orden** (modificar quantity/ticker en lugar de cancel+re-place) — Alpaca no soporta edits para Market Orders. Post-MVP solo aplica a Limit.
- **Cancelación de Limit/Stop Loss/Take Profit** — todos los Limit orders están post-MVP (F11/F12/F13).
- **Audit log dedicado por usuario** — Kibana ya cumple el rol (deuda F34 fuera de MVP).
- **Reconcile job nocturno** — el reconcile lazy on-GET cubre el MVP single-user. Job dedicado queda como deuda registrada (#8 cerrada por reconcile lazy en Día 10; v2 lo extiende a outbound transitions).
- **Recuperación de orden CANCELED** — un cancel es terminal. No se "deshace".
- **Estados de dominio adicionales** (`IN_REVIEW`, `STOPPED`, `Firmada por Comisionista`) — fuera de scope MVP. ARCHITECTURE.md §9 los lista pero no son aplicables al rol INVESTOR single-user.

---

## 14. Preguntas abiertas

> Estas son las decisiones que el SPEC **deja explícitamente para el `plan.md`**. No bloquean redacción; sí requieren resolución antes del Lote A.

1. **D-TRADING-METHOD-CANCEL** — ¿`TradingService.cancelOrder` con dispatch interno por `side` o métodos separados `cancelBuyOrder` + `cancelSellOrder`? Recomendación SPEC: **único con dispatch interno**, simétrico a F10 D-TRADING-METHOD. El flujo (validación + adapter + audit + email) es 80% común; solo la rama de refund/restore divergen.

2. **D-RESTORE-AVG-BUY-PRICE** — Cuando se cancela una SELL queued cuya posición fue eliminada (liquidación total optimista): ¿de dónde recuperamos el `avg_buy_price` para re-INSERT? Recomendación SPEC: **persistir un campo `avg_buy_price_at_submission` en `Order` para SELL** (snapshot al INSERT). Alternativa rechazada: leer del audit log — frágil y acopla audit a flujo crítico.

3. **D-RESPONSE-CODE-TIMEOUT** — ¿Polling-timeout responde 200 o 202? Recomendación SPEC: **200** (consistencia con happy-path). El frontend distingue por `cancelRequestedAt` poblado, no por status code.

4. **D-EMAIL-EXPIRED-REUSE** — ¿Reusar `order-canceled-*.html` para EXPIRED con flag, o crear `order-expired-*.html`? Recomendación SPEC: **reuso con flag**. Copy difiere en una palabra.

5. **D-DUPLICATE-CANCEL-UI** — ¿Botón cancel en PendingOrdersPanel + RecentOrdersWidget o solo en uno? Recomendación SPEC: **ambos** (decisión Q9 del SDD Paso 1). Hook único minimiza duplicación.

6. **D-RACE-FILLED-UX** — Polling devuelve RACE_FILLED: ¿tratar como EXECUTED (no refund) o forzar refund+counter-order? Recomendación SPEC: **tratar como EXECUTED**. El counter-order agrega comisión y complejidad por edge raro en paper.

7. **D-AUDIT-ORDER-CANCEL-FAILED-EMAIL** — ¿Email "no pudimos cancelar tu orden" en caso 502 `BROKER_UNAVAILABLE`? Recomendación SPEC: **NO**. El usuario verá el toast inmediato y puede reintentar. Email sería ruido.

8. **D-RECONCILE-LAZY-V2-SCOPE** — ¿v2 también materializa `partially_filled` desde Alpaca (parte fill, parte canceled)? Recomendación SPEC: **fuera de scope F15**. Market Orders raramente parcial-fillan en paper trading. Si emerge, registrar deuda post-F15.

9. **D-CANCEL-BUTTON-PLACEMENT** — Botón "Cancelar" en RecentOrdersWidget: ¿ícono solo (X) o texto "Cancelar"? Recomendación SPEC: **texto "Cancelar"** — accesibilidad + sin tooltip dependency.

10. **D-FRONTEND-INVALIDATION-STRATEGY** — Post-cancel: ¿`queryClient.invalidateQueries` granular (solo las 3 keys afectadas) o `queryClient.invalidateQueries({ refetchType: 'all' })` total? Recomendación SPEC: **granular** (`['balance']`, `['positions']`, `['recentOrders']`) — performance + previsibilidad.

---

## 15. Definition of Done específica de esta spec

- ☐ **Migración V6 aplicada** (`chk_order_status` extendida + 3 columnas nuevas). `mvn -pl backend flyway:info` lista V6.
- ☐ Endpoint `POST /api/v1/orders/{id}/cancel` documentado en Swagger UI con: body vacío, response `OrderResponse` extendido (4 campos nuevos), códigos 200 / 404 / 409 / 502.
- ☐ `OrderStatus` enum con 6 valores (PENDING, EXECUTED, REJECTED, FAILED, **CANCELED**, **EXPIRED**).
- ☐ `Order` entity con 3 campos nuevos (`cancel_requested_at`, `canceled_at`, `expired_at`) + 1 opcional (`avg_buy_price_at_submission` para SELL — D-RESTORE-AVG-BUY-PRICE).
- ☐ `TradingService.cancelOrder(userId, orderId)` con dispatch BUY/SELL + 2 excepciones nuevas (`OrderNotFoundException`, `OrderNotCancelableException`) mapeadas en `GlobalExceptionHandler`.
- ☐ `AlpacaTradingAdapter.cancelOrder(alpacaOrderId)` con polling 2s (200ms × 10) + 2 excepciones nuevas (`AlpacaOrderNotFoundException`, `AlpacaOrderNotCancelableException`).
- ☐ `OrderReconciliationService` v2 maneja `canceled / rejected / expired` outbound de Alpaca con refund/restore. Tests específicos por outcome.
- ☐ `OrderRepository.findByIdAndUserId(orderId, userId)` agregado y usado en `TradingService.cancelOrder`.
- ☐ `Notifier` +2 métodos: `notifyOrderCanceledBuy`, `notifyOrderCanceledSell`. `MailNotifier` los implementa.
- ☐ 2 templates Thymeleaf nuevas en `backend/src/main/resources/templates/`: `order-canceled-buy.html`, `order-canceled-sell.html` (inline-CSS, reuso para EXPIRED via flag de contexto).
- ☐ `AuditEventType` +6 entries: `ORDER_CANCEL_REQUESTED`, `ORDER_CANCELED`, `ORDER_EXPIRED`, `ORDER_DUPLICATE_CANCEL_REQUEST`, `ORDER_CANCEL_REJECTED`, `ORDER_CANCEL_FAILED`.
- ☐ `OrderEventListener` +1 método `handleOrderCanceled` con dispatch por side. Mismo patrón para `handleOrderExpired`.
- ☐ `ValidationMessages` +3 códigos (`ORDER_NOT_FOUND`, `ORDER_NOT_CANCELABLE`, `BROKER_UNAVAILABLE`).
- ☐ Lock pessimistic canónico `balances → positions` (heredado D17 F10) verificado en `cancelOrder`. Test IT específico de concurrencia BUY-cancel + SELL-cancel del mismo user sin deadlock.
- ☐ `BigDecimal` usado en `refundedAmount` calculations. Grep verifica ausencia de `double`/`float`.
- ☐ Frontend:
  - ☐ `useCancelOrder` hook con `useMutation` + invalidación granular.
  - ☐ `CancelOrderButton` component con confirm dialog + visual feedback `cancelRequestedAt`.
  - ☐ `PendingOrdersPanel` con columna "Acciones".
  - ☐ `RecentOrdersWidget` con botón condicional para PENDING.
  - ☐ `OrderResponse` type extendido con 4 campos + `OrderStatus` con 6 valores.
  - ☐ `messages.es.ts` +3 códigos de error + 3 códigos de éxito.
  - ☐ `ordersApi.cancelOrder` wrapper.
- ☐ Tests unitarios (`backend/src/test/java`): ≥10 escenarios nuevos en `TradingServiceTest` (BUY cancel happy, SELL cancel happy, polling-OK vs polling-timeout, RACE_FILLED, ORDER_NOT_FOUND, ORDER_NOT_CANCELABLE, idempotencia × 2, Alpaca down). ≥4 en `AlpacaTradingAdapterTest` (cancel happy, polling 10 retries, 404, 422).
- ☐ Tests de integración con WireMock (`*IT.java`): happy BUY cancel, happy SELL cancel con re-INSERT, polling-timeout flow completo + reconcile materializa en segundo GET, RACE_FILLED, cross-user 404, ORDER_NOT_CANCELABLE 409, Alpaca down 502, concurrencia × 2 cancels mismo order.
- ☐ Demo E2E manual: con mercado abierto (martes/miércoles 2026-05-27/28 8:30+ COL): colocar orden Market que quede PENDING por encolado pre-mercado en horario early-morning → /portfolio → click Cancelar → confirm dialog → toast verde + balance refunded + fila desaparece. Repetir para SELL queued.
- ☐ Demo E2E manual fallback (sin mercado abierto): mock manual de Alpaca con `paper-api.alpaca.markets` o WireMock standalone para simular flujo.
- ☐ APRENDIZAJES.md sección "Día 11 — HU-F15" en primera persona con reflexiones (foco: reconcile v2, polling como pattern reutilizable, idempotencia implícita vs explícita, decisión RACE_FILLED contra-intuitiva).
- ☐ AGENTS.md handoff actualizado con cierre F15 + Decisiones Dxx emergentes + próximo paso revamp UI (sesión separada con `frontend-design` skill).
- ☐ `mvn verify` verde sobre `feat/HU-F15-cancelar-orden`. Tests totales ≈373+ (363 actuales + ≥10 unit + ≥10 IT estimados).
- ☐ `npm run build` verde + `npm test -- --run` 27/27 todavía verde (sin tests UI nuevos — [[feedback-coverage-vs-velocidad]]).
- ☐ Sin cambios necesarios en STACK.md ni ARCHITECTURE.md (los estados ya estaban listados en §9; F15 los materializa en código + BD).

---

## Changelog

| Versión | Fecha | Autor | Cambios |
|---|---|---|---|
| 1.0 | 2026-05-26 | Juan | Versión inicial. Resuelve las 11 decisiones del SDD Paso 1 (Q1–Q11) con base en ROADMAP §3.4 y ARCHITECTURE §9. Define 6 audit events nuevos, 3 columnas BD, 2 enum values nuevos, 2 templates email, 1 endpoint REST, reconcile v2 para outbound transitions. Plan + tasks en docs separados. |
