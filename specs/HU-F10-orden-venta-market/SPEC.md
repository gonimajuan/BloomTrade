# spec.md — Orden de venta Market

---

## 1. Metadatos

| Campo | Valor |
|---|---|
| ID(s) de HU | HU-F10 (BT-14 en Jira) |
| Sprint | 2 |
| Prioridad MoSCoW | Must |
| Estado | Draft |
| Autor | Juan |
| Fecha creación | 2026-05-23 |
| Última actualización | 2026-05-23 |
| Versión spec | 1.0 |
| Día estimado del ROADMAP | Día 7 |
| HU predecesora | HU-F09 (compra Market) — montó el andamio completo de Trading + Portfolio + Alpaca |

---

## 2. Historia(s) de usuario

### HU-F10 — Realizar orden de venta Market

**Como** inversionista, **quiero** colocar una orden de venta de tipo Market sobre uno de los tickers que ya tengo en posición, **para** liquidar mi tenencia al mejor precio disponible en el mercado, conociendo de antemano la comisión que se descontará del producto y el monto neto que se acreditará a mi saldo.

### Resumen del alcance

Esta spec cubre el **ciclo de vida completo de una orden de venta Market**, reutilizando el andamio arquitectónico introducido en HU-F09 (`app.orders`, `app.positions`, `AlpacaTradingAdapter`, `MarketDataAdapter`, `CommissionManager`, `MarketScheduleManager`, `OrderEventListener`, `OrderController`):

1. **Quote previo (obligatorio)** — frontend obtiene precio actual + comisión + **producto neto estimado** (subtotal − comisión) antes de que el usuario confirme. Reutiliza `POST /api/v1/orders/quote` con `side=SELL`.
2. **Confirmación** — usuario envía `POST /api/v1/orders` con `side=SELL`, `clientOrderId` UUID v4 (idempotencia heredada).
3. **Validaciones server-side específicas de venta** — además de las de F09 (auth, ticker permitido, cantidad válida, mercado abierto stub):
   - **Posición existe** para `(userId, ticker)` → si no: `409 SHORT_SELLING_NOT_ALLOWED` (BloomTrade es long-only por diseño).
   - **Cantidad disponible** ≥ cantidad solicitada → si no: `409 INSUFFICIENT_SHARES`.
4. **Ejecución** — persistencia `PENDING` → submit Alpaca `side=sell` → respuesta final (`EXECUTED` / `REJECTED` / `FAILED` / `PENDING+alpacaOrderId` heredando D29 de F09 si Alpaca responde `accepted` no-terminal).
5. **Efectos transaccionales atómicos en caso de éxito**:
   - **Decremento de `app.positions.quantity`**.
   - **Si `quantity` resultante = 0** → `DELETE` de la fila (decisión cerrada del usuario: brokers reales operan así; HU-F16 solo lista posiciones con tenencia real).
   - **Crédito a `app.user_balances.balance`** por `executionUnitPrice × quantity − commission` (el producto neto).
   - Notificación al usuario por su canal preferido + eventos audit.
6. **Idempotencia** — heredada de F09 vía `UNIQUE (client_order_id)` + lock en memoria (D25 F09).

> **Sobre HU-F15 (cancelación):** queda fuera de esta spec — stretch goal post-MVP. Una orden SELL que quede en `PENDING+alpacaOrderId` (mercado cerrado) hoy NO se puede cancelar desde BloomTrade; el usuario debe esperar el fill o aceptar la encolada.

---

## 3. Contexto y dependencias

### Por qué importa

HU-F10 **completa el flujo de trading bidireccional** del MVP. Sin ella, el usuario solo puede comprar — su capital queda atrapado en posiciones. Es el complemento natural de F09 y juntas forman el "corazón funcional del producto" definido en ROADMAP §2.1.

Arquitectónicamente, HU-F10 es **mucho menor que F09** (~40-50% del esfuerzo estimado): el andamio (TradingService, AlpacaAdapter, MarketDataAdapter, CommissionManager, OrderOrchestrator, lock pessimistic, audit events, frontend components) ya existe. F10 agrega:

- **Validaciones nuevas** (`SHORT_SELLING_NOT_ALLOWED`, `INSUFFICIENT_SHARES`).
- **Operación inversa sobre `app.positions`** (decrement + posible DELETE) en lugar de upsert.
- **Operación inversa sobre `app.user_balances`** (credit) en lugar de debit.
- **Semántica side-aware** en `QuoteResponse.estimatedTotal` y `OrderResponse.executionTotal` (en BUY = lo que se descuenta; en SELL = lo que se acredita).
- **Templates email separadas** para venta (decisión cerrada).
- **Activación del toggle SELL** en `OrderForm` frontend.

Materializa las mismas tácticas Bass de F09: TAC-M1 (`AlpacaAdapter`), TAC-S4 (audit log), TAC-D2 (Retry), TAC-I1 (`OrderOrchestrator`), TAC-S2 (`userId` desde JWT principal). Suma una nueva: **TAC-S5 (Validar entradas) reforzada** — la validación de posición disponible es defensa contra inconsistencia entre cliente y servidor.

### Dependencias técnicas

- **HU-F09 Compra Market mergeada** — todo el andamio está en `main` (PR #6, commit `1bab23b`). Sin F09 mergeado, HU-F10 no arranca.
- **Migración V5** ya aplicada (`app.orders`, `app.positions`, `config.commission_rates`).
- **`AlpacaTradingAdapter.submitMarketOrder`** ya soporta `side="sell"` en el body (verificado en `AlpacaOrderRequest.market` línea 27 del código F09 — AGENTS.md "Estimar complejidad" §5).
- **`PortfolioService`** existe con `debit/upsertPosition/getBalance/getPositions/findBalanceProjectionByUserId` — se extiende con `credit(userId, amount)` y `decrementPosition(userId, ticker, sellQuantity)`.
- **`OrderEventListener`** existe con `@TransactionalEventListener(AFTER_COMMIT)` — se extiende con manejo de `OrderSide.SELL` para disparar el email correcto.
- **`Notifier`** ya existe con `notifyOrderExecuted/notifyOrderFailed/notifyOrderRejected` — se extiende con variantes SELL o se parametriza por side (decisión: agregar 3 métodos nuevos `*Sell` para mantener firmas claras — D-NOTIFIER-SPLIT en plan.md).
- **Frontend `OrderForm`** existe con toggle `BUY/SELL` donde SELL está `disabled` — se habilita.

### Variables de entorno nuevas

**Ninguna.** F09 ya configuró `ALPACA_API_KEY`, `ALPACA_API_SECRET`, `ALPACA_BASE_URL`, `ALPACA_DATA_BASE_URL`, `TRADING_DEFAULT_COMMISSION_PCT`. HU-F10 las reutiliza intactas.

### Migraciones BD nuevas

**Ninguna.** El schema de V5 cubre completamente F10:
- `app.orders` ya tiene `chk_order_side CHECK (side IN ('BUY', 'SELL'))` (F09 lo anticipó).
- `app.positions` ya tiene `chk_position_quantity CHECK (quantity >= 0)` (F09 lo anticipó como defensa para F10).
- `app.user_balances` no requiere cambios — `credit` es la operación inversa de `debit`.

### Features que dependen de esta

- **HU-F16 Consultar portafolio** (Día 8) — listará posiciones; F10 mantiene la invariante "fila en `app.positions` ↔ tenencia real" via DELETE en qty=0.
- **HU-F21 Consultar saldo** (Día 8) — verá los créditos por ventas reflejados.
- **HU-F18 Dashboard** (Día 9) — independiente; solo lee precios.
- **HU-F15 Cancelar orden** (stretch goal post-MVP) — relevante principalmente para órdenes SELL encoladas (D29 F09).

---

## 4. Actores y precondiciones

### Actores involucrados

| Actor | Rol del sistema | Participación |
|---|---|---|
| Usuario autenticado | INVESTOR | Iniciador del quote y de la confirmación |
| Sistema BloomTrade | — | Validador, persistente, orquestador |
| Alpaca Markets | Externo | Ejecutor de la venta (paper trading) |
| Alpaca Data API | Externo | Proveedor de precio actual del ticker |
| MailHog (dev) | Externo | Receptor de notificaciones email |
| ElasticSearch | Externo | Receptor de audit events |

### Precondiciones del sistema

- Usuario tiene sesión JWT activa con `rol = INVESTOR` y `estado = ACTIVE`.
- Usuario tiene **al menos una fila en `app.positions`** con `quantity > 0` para el ticker que va a vender.
- `app.user_balances` tiene fila para el usuario (creada en HU-F01).
- Migración V5 aplicada (sin V6 — no se introducen tablas nuevas).
- Variables Alpaca pobladas (heredadas F09).
- Alpaca paper trading account dispone del ticker en su pool global (en MVP single-user: si el usuario compró previamente en BloomTrade, Alpaca paper también tiene la posición global; ver §8.5 de F09 SPEC sobre modelo de fondos compartido).

### Datos requeridos en el sistema

- `config.commission_rates` con fila activa `(role=INVESTOR, percentage=0.02, valid_to=NULL)` — ya seedeada en V5.
- `AllowedTickers` (los 25) — ya existe.

---

## 5. Flujos

### 5.1 Flujo principal — quote + confirmación + venta exitosa

#### Paso 1: Usuario inicia venta

1. Usuario autenticado navega a `/trade`.
2. Frontend renderiza `OrderForm` con el toggle `BUY/SELL`. **Por primera vez en F10**, el botón `SELL` está habilitado.
3. Usuario activa `SELL`, selecciona ticker `AAPL` (que ya tiene en posición), e ingresa cantidad `5`.

> **Nota UI (D-UI-2 a documentar en plan.md):** en MVP, el `TickerDropdown` NO filtra por tickers que el usuario tenga en posición (esa información viene de `GET /portfolio/positions`, HU-F16 Día 8). Si el usuario selecciona un ticker sin posición y elige SELL, la validación server-side responde `409 SHORT_SELLING_NOT_ALLOWED` y el frontend renderiza el error claramente. Post-MVP / HU-F16 mergeada: filtrar el dropdown.

#### Paso 2: Quote previo (informativo, no compromete recursos)

4. Frontend envía `POST /api/v1/orders/quote` con body `{ ticker: "AAPL", side: "SELL", quantity: 5 }`.
5. `JwtAuthenticationFilter` valida JWT, `OrderController` resuelve `userId` desde el principal.
6. `TradingService.quote(userId, request)`:
   - Valida `ticker` ∈ los 25 permitidos (lanza `INVALID_TICKER` si no).
   - Valida `quantity` > 0 y ≤ `MAX_QUANTITY_PER_ORDER` (10000).
   - **Para `side=SELL` (nuevo en F10)**: verifica que exista posición y tenga cantidad suficiente:
     - Lee `app.positions` para `(userId, ticker)`.
     - Si no existe fila o `quantity = 0` → `sufficientShares = false`, `userShares = 0` en respuesta. **NO lanza excepción**: el quote es informativo (igual que `sufficientFunds=false` en F09 §5.2.1).
     - Si existe pero `quantity < sellQuantity` → `sufficientShares = false`, `userShares = quantity`.
     - Si existe y `quantity >= sellQuantity` → `sufficientShares = true`.
   - Invoca `MarketDataAdapter.getLatestPrice(ticker)` (Alpaca Data snapshot endpoint — heredado F09).
   - Invoca `CommissionManager.calculate(userRole=INVESTOR, subtotal=price×quantity)` → comisión `BigDecimal` HALF_UP scale=2.
   - Calcula `estimatedProceeds = (price × quantity) − commission`.
   - Lee `app.user_balances.balance`.
   - Invoca `MarketScheduleManager.isOpenNow(ticker)` — stub MVP siempre `true`.
7. `TradingService` retorna `QuoteResponse` y `OrderController` responde 200 con (campos nuevos para SELL en **negrita**):

   ```json
   {
     "ticker": "AAPL",
     "side": "SELL",
     "quantity": 5,
     "estimatedUnitPrice": "190.00",
     "estimatedSubtotal": "950.00",
     "commission": "19.00",
     "estimatedTotal": "931.00",
     "currency": "USD",
     "userBalance": "8116.90",
     "sufficientFunds": true,
     "sufficientShares": true,
     "userShares": 10,
     "marketOpen": true,
     "quotedAt": "2026-05-23T15:42:18Z"
   }
   ```

   > **Semántica side-aware de `estimatedTotal`** (decisión clave de F10):
   > - **BUY (F09)**: `estimatedTotal = subtotal + commission` — lo que se **descontará** del saldo.
   > - **SELL (F10)**: `estimatedTotal = subtotal − commission` — lo que se **acreditará** al saldo (producto neto).
   >
   > Misma key del JSON, semántica controlada por `side`. El frontend ya hace render side-aware. Trade-off aceptado: evita duplicar fields. Documentado en el `@Schema` del DTO (Swagger).

   > **Campos nuevos exclusivos de SELL** (siempre presentes pero solo significativos cuando `side=SELL`):
   > - `sufficientShares: boolean` — para BUY siempre `true` (no aplica).
   > - `userShares: integer` — cantidad en posición actual. Para BUY siempre `0` (no aplica) o se setea al valor actual si el ticker está en posición (informativo).

8. Frontend muestra al usuario:
   - "Precio actual estimado: USD 190.00 / acción"
   - "Subtotal: USD 950.00"
   - "Comisión (2%): USD 19.00"
   - "**Producto neto: USD 931.00**"
   - "Saldo actual: USD 8116.90 → Saldo después: USD 9047.90"
   - "Tienes 10 AAPL en posición; venderás 5; te quedarán 5"
   - Botón **"Confirmar venta"** habilitado si `sufficientShares=true && marketOpen=true`.

> **Importante:** `estimatedTotal` es **informativo**. El precio de ejecución real puede diferir (slippage). MVP no bloquea por slippage — D-SLIPPAGE de F09 sigue diferido.

#### Paso 3: Confirmación → ejecución

9. Usuario presiona "Confirmar venta".
10. Frontend genera `clientOrderId = crypto.randomUUID()` y envía `POST /api/v1/orders`:

    ```json
    {
      "clientOrderId": "a3f1b2c4-9d8e-4f7a-b1c2-3d4e5f6a7b8c",
      "ticker": "AAPL",
      "side": "SELL",
      "type": "MARKET",
      "quantity": 5
    }
    ```

11. `OrderController` resuelve `userId` desde el principal y enruta a `TradingService.placeOrder(userId, request)`.
12. `TradingService.placeOrder` (transacción JPA — reutiliza el flujo de F09 con lock idempotency en memoria — D25 F09):
    - Adquiere lock en memoria por `clientOrderId` (`ConcurrentHashMap` heredado de F09).
    - Verifica que NO exista fila en `app.orders` con `client_order_id = ...` (idempotencia — si existe, devuelve 200 con la orden existente, no entra a la lógica nueva).
    - Repite validaciones del quote (ticker permitido, cantidad válida, mercado abierto).
    - **Validaciones nuevas para SELL** (en este orden, fail-fast):
      - **Re-fetch posición con lock pessimistic** (`SELECT FOR UPDATE` sobre la fila `(userId, ticker)` de `app.positions`).
        - Si NO existe fila o `quantity = 0` → lanza `ShortSellingNotAllowedException` → 409 + rollback.
        - Si `quantity < sellQuantity` → lanza `InsufficientSharesException(available, requested)` → 409 + rollback.
      - Re-fetch del precio vía `MarketDataAdapter`.
      - Re-calcula `commission` y `estimatedProceeds`.
    - INSERT en `app.orders`:
      ```
      id=UUID, user_id, client_order_id, ticker, side='SELL', type='MARKET',
      quantity=5, quoted_unit_price=190.00, quoted_commission=19.00,
      quoted_total=931.00, status='PENDING', submitted_at=NOW()
      ```
      > **Nota:** `quoted_total` para SELL es el producto neto (subtotal − commission), consistente con la semántica side-aware del campo.
    - Invoca `AlpacaTradingAdapter.submitMarketOrder(...)` con body:
      ```
      { symbol: "AAPL", qty: 5, side: "sell", type: "market",
        time_in_force: "day", client_order_id: "a3f1b2c4-..." }
      ```
      Envuelta en `@Retry(name="alpacaApi")` (heredado F09: 3 intentos a 1s, 3s, 5s).
13. Alpaca responde con `{ id: "alp_yyy", status: "filled" | "accepted" | "rejected", filled_avg_price: "189.95", filled_qty: "5", ... }`.
14. `OrderEventListener` / `TradingService` (en la misma transacción) procesa la respuesta:
    - **Si `status="filled"`**:
      - UPDATE `app.orders` SET `status='EXECUTED'`, `alpaca_order_id`, `execution_unit_price` (= `filled_avg_price`), `execution_total` (= `filled_avg_price × quantity − commission` — producto neto), `executed_at=NOW()`.
      - **Decrement de `app.positions`**:
        - `UPDATE app.positions SET quantity = quantity - 5, updated_at = NOW() WHERE user_id = ? AND ticker = ?`
        - Si la fila resultante tiene `quantity = 0` → `DELETE FROM app.positions WHERE user_id = ? AND ticker = ?`.
        - El `avg_buy_price` **NO se modifica** en venta — sigue reflejando el precio promedio histórico de compra mientras quede tenencia (HU-F16 lo usa para calcular ganancia/pérdida).
      - **Credit a `app.user_balances`**:
        - `UPDATE app.user_balances SET balance = balance + execution_total, updated_at = NOW() WHERE user_id = ?`
        - Lock pessimistic sobre user_balances ya tomado por el flujo. El `CHECK (balance >= 0)` no se viola en venta (sumamos).
    - **Si `status="rejected"`**:
      - UPDATE `app.orders` SET `status='REJECTED'`, `error_code='ALPACA_ORDER_REJECTED'`, `error_message=...`.
      - **NO se decrementa posición, NO se acredita balance**. La posición queda intacta.
    - **Si `status="accepted"` (no terminal — D29 F09 heredado)**:
      - UPDATE `app.orders` SET `status='PENDING'`, `alpaca_order_id` poblado, sin `executed_at`.
      - **DECISIÓN HEREDADA F09 D29:** la posición YA se decrementa optimistamente y el balance NO se acredita aún (porque no conocemos el `executionUnitPrice` real). Cuando Alpaca filee posteriormente, la reconciliación (job nocturno o webhook — deuda registrada AGENTS #8 + #13) actualiza el balance.
      - **Trade-off documentado como D-SELL-QUEUED-RISK en plan.md:** si Alpaca cancela una orden encolada (caso edge), el usuario perdió posición sin recibir crédito. MVP no mitiga; queda como deuda. La probabilidad es baja en testing single-user en horario de mercado.
    - COMMIT.
15. **Post-commit** (`@TransactionalEventListener(AFTER_COMMIT)` heredado F09):
    - `Notifier.notifyOrderExecutedSell(userId, order)` → email "Vendiste 5 AAPL a USD 189.95. Producto neto: USD 930.75. Tu saldo actual: USD 9047.65."
    - `Auditor.emit(ORDER_CREATED)` + `Auditor.emit(ORDER_EXECUTED)` con `details: { ..., side: "SELL", positionResultingQty: 5 }` (o `positionDeleted: true` si la fila se borró).
16. Backend responde 201 Created con:

    ```json
    {
      "id": "ord_uuid",
      "clientOrderId": "a3f1b2c4-9d8e-4f7a-b1c2-3d4e5f6a7b8c",
      "ticker": "AAPL",
      "side": "SELL",
      "type": "MARKET",
      "quantity": 5,
      "quotedUnitPrice": "190.00",
      "executionUnitPrice": "189.95",
      "commission": "19.00",
      "quotedTotal": "931.00",
      "executionTotal": "930.75",
      "status": "EXECUTED",
      "alpacaOrderId": "alp_yyy",
      "submittedAt": "2026-05-23T15:42:25Z",
      "executedAt": "2026-05-23T15:42:25Z"
    }
    ```

17. Frontend muestra toast emerald `OrderConfirmationToast`: "✅ Vendiste 5 AAPL a USD 189.95 — Recibiste USD 930.75". Permite otra orden inmediata (decisión MVP-friendly heredada F09 D-UI-1).

**Postcondiciones del flujo principal:**

- Fila nueva en `app.orders` con `side='SELL'`, `status='EXECUTED'`.
- `app.positions` para `(userId, AAPL)` tiene `quantity = 5` (o fila borrada si era exactamente 5 antes).
- `app.user_balances.balance` incrementado por `execution_total` (producto neto).
- Email "Venta ejecutada" en MailHog.
- Eventos en ElasticSearch: `ORDER_CREATED`, `ORDER_EXECUTED` con `side=SELL`.
- Alpaca paper tiene la venta registrada (visible en dashboard de Alpaca).

### 5.2 Flujos alternativos

#### 5.2.1 Quote sin posición suficiente (informativo, no bloqueante)

**Cuándo se activa:** `POST /orders/quote` con `side=SELL` cuando el usuario no tiene posición o tiene menos cantidad de la que quiere vender.

**Comportamiento:**

- El endpoint responde 200 normalmente con `sufficientShares: false` y `userShares: <cantidad actual o 0>`.
- Frontend deshabilita el botón "Confirmar venta" y muestra:
  - Si `userShares = 0`: "No tienes posición en este ticker."
  - Si `0 < userShares < quantity`: "Solo tienes {userShares} acciones disponibles para vender."
- NO se considera error — el quote es siempre informativo.

#### 5.2.2 Quote con mercado cerrado

**MVP:** `MarketScheduleManager.isOpenNow(ticker)` siempre devuelve `true` (stub heredado F09). Rama no activa.

**Heredado F09 D29:** si en submit Alpaca responde `accepted` no-terminal, la orden queda `PENDING + alpacaOrderId`; ver §5.1 paso 14.

#### 5.2.3 Orden con `clientOrderId` duplicado (idempotencia)

**Heredado F09 §5.2.3 sin cambios.** El check por `client_order_id` UNIQUE + el lock en memoria (D25 F09) cubren tanto BUY como SELL. Una segunda request con el mismo `clientOrderId` retorna 200 con la orden existente (sea BUY o SELL), sin tocar posición ni balance.

### 5.3 Flujos de error

#### 5.3.1 Ticker inválido (no en lista de 25)

**Heredado F09 §5.3.1 sin cambios.** 400 + `INVALID_TICKER`.

#### 5.3.2 Cantidad inválida

**Heredado F09 §5.3.2 sin cambios.** 400 + `INVALID_QUANTITY`.

#### 5.3.3 Side inválido

**Modificación respecto a F09:** ya NO se devuelve `SIDE_NOT_YET_IMPLEMENTED` para `side=SELL` — F10 implementa el handler. El código `SIDE_NOT_YET_IMPLEMENTED` se mantiene en `messages.es.ts` para retro-compatibilidad pero deja de emitirse desde el backend.

**Sigue siendo 400 + `INVALID_SIDE`** si llega cualquier otro valor distinto de `BUY` o `SELL`.

#### 5.3.4 Short selling (no tiene la posición)

**Nuevo en F10.**

**Cuándo se dispara:** `POST /orders` con `side=SELL` cuando no existe fila en `app.positions` para `(userId, ticker)` o cuando la fila tiene `quantity = 0`.

**Respuesta:** HTTP 409 Conflict con código `SHORT_SELLING_NOT_ALLOWED` y `message: "No tienes posición en {ticker}. BloomTrade no permite ventas en corto."`.

**Estado final:** Rollback. La fila `app.orders` (si llegó a INSERT) se elimina. No se llama a Alpaca. Posición intacta.

**Evento de auditoría:** `ORDER_REJECTED` con `details: { reason: "SHORT_SELLING_NOT_ALLOWED", ticker, requestedQty }`.

#### 5.3.5 Acciones insuficientes (tiene la posición pero menos cantidad)

**Nuevo en F10.**

**Cuándo se dispara:** `POST /orders` con `side=SELL` cuando existe fila en `app.positions` con `quantity > 0` pero `quantity < sellQuantity`.

**Respuesta:** HTTP 409 Conflict con código `INSUFFICIENT_SHARES` y `details: { available: 3, requested: 5, ticker: "AAPL" }`.

**Estado final:** Rollback. Posición intacta.

**Evento de auditoría:** `ORDER_REJECTED` con `details: { reason: "INSUFFICIENT_SHARES", available, requested, ticker }`.

**Notificación:** **NO se envía email** (mismo principio F09 §9.2: el usuario ya vio el error en pantalla; email sería ruido).

#### 5.3.6 Alpaca API caída (post-retries)

**Heredado F09 §5.3.5 sin cambios semánticos.** 502 + `ALPACA_API_ERROR`. La fila `app.orders` queda `status='FAILED'`.

**Diferencia clave en SELL:** la posición y el balance **NO se modifican** porque el flujo entero está en una transacción JPA — el rollback revierte tanto el INSERT order como cualquier `UPDATE app.positions` o `UPDATE app.user_balances` que se haya intentado.

> El riesgo "Alpaca recibió + commit local falló" es simétrico al de F09 (deuda reconciliación post-MVP). En SELL, la asimetría es: la venta queda registrada en Alpaca pero BloomTrade no la refleja → posición local "fantasma" más alta que la real.

#### 5.3.7 Alpaca rechaza la orden explícitamente

**Heredado F09 §5.3.6 sin cambios semánticos.** 422 + `ALPACA_ORDER_REJECTED`. Posición y balance intactos.

**Razones específicas comunes en SELL:**
- "asset not tradeable" — si Alpaca delistó el ticker (raro para los 25 majors).
- "qty exceeds buying power" — no aplica para SELL (solo BUY); ignorar.
- "wash trade detected" — si el usuario compró y vende muy rápido el mismo ticker. Comportamiento Alpaca paper es laxo; documentado en notificación.

#### 5.3.8 `clientOrderId` faltante o malformado

**Heredado F09 §5.3.7 sin cambios.** 400 + `VALIDATION_REQUIRED` o `INVALID_CLIENT_ORDER_ID`.

#### 5.3.9 Alpaca Data API caída al pedir quote

**Heredado F09 §5.3.8 sin cambios.** 502 + `MARKET_DATA_UNAVAILABLE`.

#### 5.3.10 Usuario no autenticado / cuenta no activa

**Heredado F09 §5.3.9 + §5.3.10 sin cambios.** 401 `AUTHENTICATION_REQUIRED` o 403 `ACCOUNT_NOT_ACTIVE`.

#### 5.3.11 Concurrencia: dos ventas simultáneas del mismo ticker que juntas exceden la posición

**Análogo a F09 §5.3.11 pero sobre `app.positions` en lugar de `app.user_balances`.**

**Cuándo se dispara:** Usuario tiene 10 AAPL y dispara dos ventas simultáneas de 8 cada una.

**Comportamiento:** El lock pessimistic (`SELECT FOR UPDATE`) sobre la fila `(userId, ticker)` de `app.positions` serializa las ejecuciones. La primera commitea (queda con `quantity=2`); la segunda re-evalúa y falla con `INSUFFICIENT_SHARES` (§5.3.5).

> **Invariante BD:** `CHECK (quantity >= 0)` en `app.positions` es defensa en profundidad. Si la lógica de validación tuviera un bug, el INSERT/UPDATE que dejara `quantity < 0` haría fallar la transacción completa.

#### 5.3.12 Venta de posición justo después de DELETE concurrente

**Edge muy específico:** usuario tiene exactamente 5 AAPL; dispara dos ventas simultáneas de 5 cada una. La primera ejecuta y borra la fila; la segunda al adquirir el lock encuentra la fila ya borrada → `SHORT_SELLING_NOT_ALLOWED` (§5.3.4) — el handler distingue "fila no existe" de "fila con qty=0" pero ambos casos responden el mismo código de error.

---

## 6. Contratos de datos

### 6.1 Endpoints reutilizados

**Sin endpoints nuevos.** F10 reutiliza los 2 endpoints de F09 con `side=SELL`:

| Endpoint | Comportamiento en F10 |
|---|---|
| `POST /api/v1/orders/quote` | Acepta `side=SELL`. Responde con campos nuevos `sufficientShares: boolean` y `userShares: integer` (siempre presentes; significativos solo en SELL). `estimatedTotal` con semántica side-aware (SELL = subtotal − commission). |
| `POST /api/v1/orders` | Acepta `side=SELL`. Mismo response shape (`OrderResponse`) con `executionTotal` side-aware (SELL = filled_avg_price × qty − commission). Códigos nuevos posibles: `SHORT_SELLING_NOT_ALLOWED`, `INSUFFICIENT_SHARES`. |

### 6.2 Modificaciones a schemas existentes

#### 6.2.1 `QuoteResponse` (extensión retro-compatible)

```yaml
components:
  schemas:
    QuoteResponse:
      type: object
      required:
        - ticker
        - side
        - quantity
        - estimatedUnitPrice
        - estimatedSubtotal
        - commission
        - estimatedTotal
        - currency
        - userBalance
        - sufficientFunds
        - sufficientShares  # NUEVO en F10
        - userShares        # NUEVO en F10
        - marketOpen
        - quotedAt
      properties:
        # ... campos heredados de F09 sin cambios ...
        estimatedTotal:
          type: string
          description: |
            Side-aware:
            - BUY: subtotal + commission (lo que se descontará del saldo).
            - SELL: subtotal - commission (lo que se acreditará al saldo, producto neto).
          example: "931.00"
        sufficientShares:
          type: boolean
          description: |
            Indica si el usuario tiene suficiente cantidad para vender.
            - Para BUY siempre true (no aplica).
            - Para SELL: true si app.positions.quantity para (userId, ticker) >= quantity solicitada.
          example: true
        userShares:
          type: integer
          description: |
            Cantidad actual del usuario para el ticker consultado.
            - Para BUY: 0 si no tiene posición, o la cantidad actual si la tiene (informativo).
            - Para SELL: cantidad actual disponible para validar contra quantity solicitada.
          example: 10
```

**Retro-compatibilidad:** los clientes F09 (que solo hacen BUY) reciben los 2 campos nuevos pero los ignoran. Sin breaking change.

#### 6.2.2 `OrderResponse` (sin cambios estructurales; cambio semántico documentado)

```yaml
OrderResponse:
  # ... shape idéntico a F09 ...
  properties:
    quotedTotal:
      type: string
      description: |
        Side-aware:
        - BUY: subtotal + commission (lo que se cobrará si la ejecución coincide con el quote).
        - SELL: subtotal - commission (lo que se acreditará si la ejecución coincide con el quote).
    executionTotal:
      type: string
      description: |
        Side-aware:
        - BUY: execution_unit_price × quantity + commission (lo cobrado real).
        - SELL: execution_unit_price × quantity - commission (lo acreditado real).
```

### 6.3 Códigos de error nuevos

| Código HTTP | Código aplicación | Cuándo | Detalles |
|---|---|---|---|
| 409 | `SHORT_SELLING_NOT_ALLOWED` | No existe posición o `quantity=0` | `{ ticker, requestedQty }` |
| 409 | `INSUFFICIENT_SHARES` | Existe posición con `quantity < requestedQty` | `{ available, requested, ticker }` |

Los demás códigos (400 `INVALID_TICKER` / `INVALID_QUANTITY` / `INVALID_SIDE` / `INVALID_CLIENT_ORDER_ID`, 401 `AUTHENTICATION_REQUIRED`, 403 `ACCOUNT_NOT_ACTIVE`, 422 `ALPACA_ORDER_REJECTED`, 502 `ALPACA_API_ERROR` / `MARKET_DATA_UNAVAILABLE`) se reutilizan de F09 sin cambios.

> **Importante:** `INSUFFICIENT_FUNDS` (heredado F09) NO se emite en SELL — vender SIEMPRE acredita saldo, nunca lo descuenta. La comisión sale del producto, no del balance.

---

## 7. Cambios en base de datos

### 7.1 Migración a crear

**Ninguna.** F10 reutiliza V5 completa. El schema F09 fue diseñado anticipando F10:

- `app.orders.chk_order_side` ya acepta `'BUY'` y `'SELL'`.
- `app.positions.chk_position_quantity` ya es `CHECK (quantity >= 0)` — necesario para DELETE de la fila cuando llega a 0.
- `app.user_balances.chk_balance_non_negative` no se viola en SELL (sumar nunca puede llevar a negativo).

### 7.2 Modificaciones a tablas existentes

**Ninguna.**

### 7.3 Datos semilla

**Ninguno nuevo.** Comisión 2% INVESTOR ya seedeada en V5.

> **Verificación previa a Lote A de implementación:** correr `psql -d bloomtrade -c "\d app.orders"` y confirmar `chk_order_side` incluye `'SELL'`. Si por alguna razón V5 quedó con solo `'BUY'` (no debería — el SPEC F09 §7.2 lo cubrió), entonces F10 requeriría V6 `ALTER TABLE app.orders DROP CONSTRAINT chk_order_side; ADD CONSTRAINT chk_order_side CHECK (side IN ('BUY', 'SELL'));`. **Spec asume V5 correcto.**

---

## 8. Mapeo arquitectónico

### 8.1 Módulos involucrados

Mismos que F09 — sin módulos nuevos:

| Módulo | Rol en F10 | Cambios concretos |
|---|---|---|
| TradingService | Iniciador + orquestador | Extender `placeOrderTx` con rama `side=SELL` (handler nuevo en la misma clase o método `placeOrderSell` separado — decisión D-TRADING-METHOD en plan.md). 2 excepciones nuevas. |
| PortfolioService | Notificado (escritura) | Agregar `credit(userId, amount)` (inverso de `debit`). Agregar `decrementPosition(userId, ticker, sellQuantity)` que retorna `Position` actualizada o `null` si la fila se borró por `quantity=0`. Lock pessimistic sobre `app.positions`. |
| IntegrationService | Intermediario | **Sin cambios.** `AlpacaTradingAdapter.submitMarketOrder` ya soporta `side="sell"` (D6 F09). `MarketDataAdapter.getLatestPrice` se reutiliza. |
| AdminService | Proveedor de configuración | **Sin cambios.** `CommissionManager.calculate(role, subtotal)` se reutiliza idéntico. |
| NotificationService | Notificado (despacho async) | Agregar 3 métodos al `Notifier`: `notifyOrderExecutedSell`, `notifyOrderRejectedSell`, `notifyOrderFailedSell`. Implementar en `MailNotifier`. Crear 3 templates Thymeleaf nuevos. **Renombrar** las 3 templates F09 a sufijo `-buy` para consistencia (`order-executed.html` → `order-executed-buy.html`, etc.). |
| AuditService | Notificado (registro) | **Sin event types nuevos.** Los 6 existentes (ORDER_CREATED, ORDER_EXECUTED, ORDER_REJECTED, ORDER_FAILED, ORDER_DUPLICATE_REQUEST, ORDER_BLOCKED_BY_ACCOUNT_STATUS) + QUOTE_FAILED se reutilizan; el `side` y los detalles de venta van en el `details` JSON. |

> **Decisión arquitectónica clave:** F10 es la primera HU que **pura y simplemente reutiliza el andamio** de una HU previa sin agregar componentes estructurales nuevos. Esto es la **validación retrospectiva del diseño de F09** — el andamio fue anticipatorio y se paga aquí.

### 8.2 Interfaces consumidas

Mismas que F09. Sin nuevas dependencies.

### 8.3 Interfaces expuestas

| Interfaz | Quién la consumirá | Contrato nuevo o modificado |
|---|---|---|
| `TradingService.placeOrder(userId, request)` | `OrderController` | Sin cambio de firma — el `side` viene en `request`. Internamente delega a la rama correspondiente. |
| `PortfolioService.credit(userId, amount)` | `TradingService` rama SELL | **Nuevo.** Suma `amount` a `app.user_balances.balance` con lock pessimistic. Análogo a `debit`. |
| `PortfolioService.decrementPosition(userId, ticker, sellQuantity)` | `TradingService` rama SELL | **Nuevo.** Decrementa `app.positions.quantity` por `sellQuantity`. Si resultante = 0, DELETE de la fila. Retorna `Optional<Position>` (`Optional.empty()` si se borró). Lanza `InsufficientSharesException` o `ShortSellingNotAllowedException` si las precondiciones no se cumplen (defensa en profundidad — el servicio caller también valida). |
| `Notifier.notifyOrderExecutedSell(userId, order)` | `OrderEventListener` | **Nuevo.** Email con plantilla `order-executed-sell.html`. |
| `Notifier.notifyOrderRejectedSell(userId, order)` | `OrderEventListener` | **Nuevo.** |
| `Notifier.notifyOrderFailedSell(userId, order)` | `OrderEventListener` | **Nuevo.** |

> **Decisión D-NOTIFIER-SPLIT** (a documentar en plan.md): Notifier expone 6 métodos en total (3 BUY + 3 SELL) en lugar de 3 parametrizados por side. Razones: (a) wording diferente (cobrar vs recibir) hace los templates lo suficientemente distintos como para no compartir lógica; (b) firmas explícitas simplifican test assertion (mock fácil de verificar); (c) Notifier es interfaz pequeña — 6 métodos es manejable.

### 8.4 Tácticas de Bass aplicadas

Mismas que F09. Nada nuevo. Reforzamos **TAC-S5 (Validar entradas)** con las 2 validaciones nuevas (`SHORT_SELLING_NOT_ALLOWED`, `INSUFFICIENT_SHARES`).

### 8.5 Modelo de fondos: BloomTrade vs Alpaca paper

**Sin cambios respecto a F09 §8.5.** La asimetría sigue: BloomTrade es la fuente de verdad por usuario, Alpaca paper es la fuente de verdad global. En MVP single-user no hay drift relevante.

**Caso edge específico de SELL en multi-user (post-MVP):** si dos usuarios A y B compraron 10 AAPL cada uno (Alpaca tiene 20 AAPL en su pool global), y luego A vende 10 — Alpaca queda con 10 y BloomTrade refleja A=0 + B=10. Coherente. Pero si Alpaca tuviera por alguna razón menos AAPL en pool (por ejemplo, comisión inicial del paper account), la venta de A podría fallar en Alpaca incluso con A=10 en BloomTrade. Edge improbable en testing MVP; documentado en deuda reconciliación.

---

## 9. Efectos colaterales

### 9.1 Eventos de auditoría

**Sin event types nuevos.** Los 6 + QUOTE_FAILED de F09 se reutilizan; el `side` y detalles van en `details`:

| `event_type` | Trigger en F10 | `details` enriquecido |
|---|---|---|
| `ORDER_CREATED` | INSERT en `app.orders` con `side='SELL'` | `{ orderId, clientOrderId, ticker, side: "SELL", type, quantity, quotedTotal }` |
| `ORDER_EXECUTED` | UPDATE a `status=EXECUTED` post-Alpaca | `{ orderId, alpacaOrderId, side: "SELL", executionUnitPrice, executionTotal, commission, positionResultingQty: 5, positionDeleted: false }` (o `positionDeleted: true` si se borró la fila) |
| `ORDER_REJECTED` | Rechazo (Alpaca, SHORT_SELLING_NOT_ALLOWED, INSUFFICIENT_SHARES, ticker inválido) | `{ orderId, side: "SELL", reason, details }` |
| `ORDER_FAILED` | Alpaca down post-retries | Igual que F09 + `side: "SELL"` |
| `ORDER_DUPLICATE_REQUEST` | clientOrderId duplicado | Igual que F09 |
| `ORDER_BLOCKED_BY_ACCOUNT_STATUS` | Usuario blocked/suspended | Igual que F09 |
| `QUOTE_FAILED` | Alpaca Data down | `{ ticker, side, reason }` |

> **Decisión:** NO agregar `POSITION_DEPLETED` como event type separado. La señal "fila borrada" va en `ORDER_EXECUTED.details.positionDeleted`. Mantener el catálogo de event types pequeño facilita el dashboard Kibana.

### 9.2 Notificaciones

Todas se envían vía el `notificationChannel` del usuario (default EMAIL → MailHog).

| Trigger | Plantilla | Contenido resumido |
|---|---|---|
| `ORDER_EXECUTED` con `side=SELL` | `order-executed-sell.html` (nueva) | "Tu orden de venta de {quantity} {ticker} se ejecutó a USD {executionUnitPrice}. Producto neto acreditado: USD {executionTotal} (subtotal USD {subtotal} − comisión USD {commission}). Tu saldo actual: USD {newBalance}. Posición restante: {positionResultingQty} {ticker}." |
| `ORDER_REJECTED` (Alpaca) con `side=SELL` | `order-rejected-sell.html` (nueva) | "Tu orden de venta de {quantity} {ticker} fue rechazada por el mercado: {alpacaReason}. Tu posición no fue modificada y tu saldo no fue afectado." |
| `ORDER_FAILED` (Alpaca/Data down) con `side=SELL` | `order-failed-sell.html` (nueva) | "Tu orden de venta de {quantity} {ticker} no pudo procesarse por un error técnico. Tu posición y saldo no fueron afectados. Intenta nuevamente en unos minutos." |

> **Decisión cerrada del usuario:** templates separadas por side, no condicional Thymeleaf. Razones:
> - Wording difiere ("recibirás" vs "se descontará"; "producto neto" vs "total a pagar").
> - Renombrar las 3 templates F09 a sufijo `-buy` es un tirón de Git limpio (`git mv`).
> - Templates Thymeleaf con condicionales `th:if=side` mezclan copy con lógica.
>
> **No se envía email** para `SHORT_SELLING_NOT_ALLOWED` ni `INSUFFICIENT_SHARES` (mismo principio F09: error inmediato visible en pantalla, email sería ruido).

### 9.3 Cambios en caché Redis

**No aplica.** F10 no introduce cache (igual que F09). HU-F18 lo agregará.

### 9.4 Llamadas a APIs externas

| API | Método | Adapter | Cuándo |
|---|---|---|---|
| Alpaca Data API | `GET /v2/stocks/{ticker}/snapshot` | `MarketDataAdapter` | Cada quote y cada submit (re-fetch). Idéntico a F09. |
| Alpaca Markets (paper) | `POST /v2/orders` con `side: "sell"` | `AlpacaTradingAdapter` | Cada `POST /orders` con `side=SELL` exitoso post-validaciones. |

**RetryPolicy:** heredada F09 (`alpacaApi` y `alpacaData`, 3×1s/3s/5s). Configuración `application.yml` sin cambios.

**Idempotency hacia Alpaca:** `client_order_id` se envía igual que en BUY — Alpaca lo respeta como dedup nativo.

---

## 10. Atributos de calidad aplicables

### 10.1 Escenarios de calidad referenciados

Mismos que F09. La capacidad de respuesta (ESC-I1, <5s end-to-end) aplica igual.

### 10.2 Constraints específicos de esta feature

| Constraint | Medida | Cómo se verifica |
|---|---|---|
| `POST /orders/quote` con `side=SELL` responde en <2s p95 | 20 requests serializadas | `time curl` o JMeter mini |
| `POST /orders` con `side=SELL` responde en <5s p95 | 20 requests con posición suficiente | `time curl` o JMeter |
| `BigDecimal` usado para todo monto incluso en credit/decrement | Grep | Sin `double`/`float` en `PortfolioService.credit` |
| Comisión calculada con `HALF_UP` y 2 decimales también en SELL | Test parametrizado | Cases: subtotal 1234.567 × 0.02 = 24.69 (no 24.6913) |
| `app.positions.quantity` NUNCA negativo | CHECK constraint BD + lock | Test IT: dos ventas simultáneas mismo ticker — una falla con `INSUFFICIENT_SHARES` |
| `app.user_balances.balance` solo se INCREMENTA en venta | Test IT | Assert: balance final > balance inicial post-SELL exitosa |
| DELETE de `app.positions` cuando `quantity` llega a 0 | Test IT | Assert: `SELECT COUNT(*) FROM app.positions WHERE user_id=? AND ticker=?` retorna 0 tras venta total |
| Idempotencia por `client_order_id` cubre SELL también | Test IT N=10 | Assert: 1 fila, 9 responses 200 con mismo id, posición decrementada UNA vez |
| `OrderController` rechaza `?userId=other-uuid` en SELL | Test | Análogo a F09 |
| Slippage informativo en `OrderResponse.executionTotal` | Test IT | Assert: si Alpaca filea a precio distinto al quote, `executionTotal != quotedTotal` y ambos están poblados |
| `SHORT_SELLING_NOT_ALLOWED` se valida antes de llamar a Alpaca | Test IT | Assert: WireMock NO recibió invocación |
| `INSUFFICIENT_SHARES` se valida antes de llamar a Alpaca | Test IT | Mismo |
| Rollback completo si Alpaca rechaza SELL | Test IT con WireMock | Posición intacta, balance intacto, fila `app.orders` con `status=REJECTED` |
| Concurrencia: dos ventas simultáneas que juntas exceden posición → UNA falla | Test IT CompletableFuture × 2 | Una EXECUTED, una INSUFFICIENT_SHARES |

---

## 11. Criterios de aceptación

### 11.1 Escenarios de aceptación

```gherkin
Funcionalidad: Orden de venta Market

  Antecedentes:
    Dado un usuario autenticado "juan@example.com" con saldo USD 8116.90 y rol INVESTOR
    Y el usuario tiene posición { ticker: "AAPL", quantity: 10, avg_buy_price: 184.62 } en app.positions
    Y la tasa de comisión INVESTOR está en 0.02 (2%) en config.commission_rates
    Y MarketScheduleManager.isOpenNow(*) devuelve true (stub MVP)
    Y Alpaca Data API devuelve precio 190.00 para AAPL al consultar snapshot

  Escenario: Quote informativo de venta con posición suficiente
    Cuando el usuario envía POST /api/v1/orders/quote con { ticker: "AAPL", side: "SELL", quantity: 5 }
    Entonces el sistema responde 200 con
      | campo                | valor      |
      | estimatedUnitPrice   | "190.00"   |
      | estimatedSubtotal    | "950.00"   |
      | commission           | "19.00"    |
      | estimatedTotal       | "931.00"   |
      | userBalance          | "8116.90"  |
      | sufficientShares     | true       |
      | userShares           | 10         |
      | marketOpen           | true       |
    Y NO se persiste ninguna orden en app.orders
    Y NO se llama a Alpaca trading

  Escenario: Quote con posición insuficiente
    Cuando el usuario envía POST /orders/quote con { ticker: "AAPL", side: "SELL", quantity: 20 }
    Entonces el sistema responde 200 con sufficientShares=false y userShares=10
    Y el frontend deshabilita el botón "Confirmar venta" y muestra "Solo tienes 10 acciones disponibles"

  Escenario: Quote sin posición (short selling intent)
    Cuando el usuario envía POST /orders/quote con { ticker: "MSFT", side: "SELL", quantity: 1 }
    Y el usuario NO tiene posición en MSFT
    Entonces el sistema responde 200 con sufficientShares=false y userShares=0
    Y el frontend deshabilita "Confirmar venta" y muestra "No tienes posición en este ticker"

  Escenario: Orden de venta ejecutada exitosamente con posición restante
    Cuando el usuario envía POST /api/v1/orders con
      | clientOrderId    | a3f1b2c4-9d8e-4f7a-b1c2-3d4e5f6a7b8c |
      | ticker           | AAPL                                  |
      | side             | SELL                                  |
      | type             | MARKET                                |
      | quantity         | 5                                     |
    Y Alpaca responde { id: "alp_2", status: "filled", filled_avg_price: "189.95", filled_qty: "5" }
    Entonces el sistema responde 201 con status="EXECUTED"
    Y app.orders tiene fila con side="SELL", status=EXECUTED, execution_unit_price=189.95, execution_total=930.75
    Y app.positions tiene fila { ticker: "AAPL", quantity: 5, avg_buy_price: 184.62 }   # avg_buy_price NO cambia
    Y app.user_balances.balance pasa de 8116.90 a 9047.65
    Y se emite ORDER_CREATED y ORDER_EXECUTED a ElasticSearch con side="SELL" y positionResultingQty=5
    Y MailHog recibe email "Vendiste 5 AAPL a USD 189.95 — recibiste USD 930.75"

  Escenario: Venta total elimina la fila de la posición
    Dado que el usuario tiene posición { ticker: "AAPL", quantity: 5, avg_buy_price: 184.62 }
    Cuando ejecuta venta de las 5 AAPL a 189.95
    Entonces app.positions NO tiene fila para (userId, AAPL)
    Y app.user_balances refleja el crédito
    Y ORDER_EXECUTED.details tiene positionDeleted=true

  Escenario: Orden de venta con clientOrderId duplicado retorna la existente
    Dado que ya existe una orden SELL con clientOrderId="a3f1b2c4-..." y status="EXECUTED"
    Cuando el usuario envía POST /api/v1/orders con el mismo clientOrderId
    Entonces el sistema responde 200 (no 201) con la orden existente
    Y NO se modifica app.positions ni app.user_balances
    Y NO se llama a Alpaca
    Y se emite ORDER_DUPLICATE_REQUEST a auditoría

  Escenario: Short selling bloqueado por servidor
    Dado que el usuario NO tiene posición en MSFT
    Cuando envía POST /api/v1/orders con { ticker: "MSFT", side: "SELL", quantity: 1, clientOrderId: "<uuid>" }
    Entonces el sistema responde 409 con código SHORT_SELLING_NOT_ALLOWED
    Y app.positions sigue sin fila para (userId, MSFT)
    Y se emite ORDER_REJECTED con reason="SHORT_SELLING_NOT_ALLOWED"

  Escenario: Cantidad insuficiente bloqueada por servidor
    Dado que el usuario tiene posición { ticker: "AAPL", quantity: 3 }
    Cuando envía POST /api/v1/orders con { ticker: "AAPL", side: "SELL", quantity: 5, clientOrderId: "<uuid>" }
    Entonces el sistema responde 409 con código INSUFFICIENT_SHARES
    Y la respuesta incluye details { available: 3, requested: 5, ticker: "AAPL" }
    Y app.positions.quantity sigue en 3
    Y se emite ORDER_REJECTED con reason="INSUFFICIENT_SHARES"

  Escenario: Alpaca caída en SELL post-retries
    Dado que Alpaca devuelve 503 a las 3 llamadas (1s, 3s, 5s)
    Cuando el usuario envía POST /api/v1/orders SELL válido
    Entonces el sistema responde 502 con código ALPACA_API_ERROR
    Y app.orders tiene fila con side="SELL", status="FAILED"
    Y app.positions NO se modificó
    Y app.user_balances NO se modificó
    Y se envía email "Tu orden de venta no pudo procesarse"

  Escenario: Alpaca rechaza la venta
    Dado que Alpaca responde { status: "rejected", reject_reason: "wash trade" }
    Cuando el usuario envía POST /api/v1/orders SELL válido
    Entonces el sistema responde 422 con código ALPACA_ORDER_REJECTED
    Y app.orders tiene side="SELL", status="REJECTED"
    Y app.positions y app.user_balances NO se modificaron

  Escenario: Alpaca Data API caída al pedir quote SELL
    Dado que Alpaca Data devuelve 503 a las 3 llamadas
    Cuando el usuario envía POST /api/v1/orders/quote con side=SELL
    Entonces el sistema responde 502 con código MARKET_DATA_UNAVAILABLE

  Escenario: Concurrencia — dos ventas simultáneas que juntas exceden posición
    Dado que el usuario tiene posición { ticker: "AAPL", quantity: 5 }
    Cuando se envían POST /orders simultáneamente con
      | request | clientOrderId | quantity |
      | A       | <uuid-1>      | 3        |
      | B       | <uuid-2>      | 3        |
    Entonces exactamente UNA orden queda EXECUTED
    Y la otra responde 409 INSUFFICIENT_SHARES
    Y app.positions.quantity refleja la única ejecutada (5 - 3 = 2)

  Escenario: Venta encolada por Alpaca accepted no-terminal (D29 F09 heredado)
    Dado que Alpaca responde { id: "alp_3", status: "accepted" }
    Cuando el usuario envía POST /api/v1/orders SELL válido
    Entonces el sistema responde 201 con status="PENDING" y alpacaOrderId="alp_3"
    Y app.positions.quantity SÍ se decrementó (optimistic)
    Y app.user_balances NO se acreditó (esperando fill real)
    Y MailHog recibe email "Tu orden de venta se encoló — recibirás el crédito al ejecutarse"
    Y la deuda de reconciliación queda registrada (AGENTS.md #8 #13)
```

### 11.2 Trazabilidad criterios → escenarios

| Criterio de aceptación HU-F10 | Escenario Gherkin que lo cubre |
|---|---|
| E1: Usuario obtiene quote con producto neto + comisión + saldo proyectado ANTES de confirmar | "Quote informativo de venta con posición suficiente" |
| E2: Usuario ejecuta venta exitosa con crédito al balance y decremento de posición | "Orden de venta ejecutada exitosamente con posición restante" |
| E3: Venta total elimina la fila de `app.positions` | "Venta total elimina la fila de la posición" |
| E4: Idempotencia por `clientOrderId` también en SELL | "Orden de venta con clientOrderId duplicado retorna la existente" |
| E5: Short selling bloqueado | "Short selling bloqueado por servidor" |
| E6: Cantidad insuficiente bloqueada | "Cantidad insuficiente bloqueada por servidor" |
| E7: Alpaca down → FAILED sin modificación de posición/balance | "Alpaca caída en SELL post-retries" |
| E8: Alpaca rechaza → REJECTED sin modificación | "Alpaca rechaza la venta" |
| E9: Alpaca Data down → quote falla | "Alpaca Data API caída al pedir quote SELL" |
| E10: Concurrencia preserva invariante `quantity >= 0` | "Concurrencia — dos ventas simultáneas..." |
| E11: D29 F09 (encoladas) heredado en SELL con risk documentado | "Venta encolada por Alpaca accepted no-terminal" |
| E12: Producto neto y comisión mostrados ANTES de confirmar (ARCH §9 análogo) | "Quote informativo..." (campos `commission`, `estimatedTotal`) |
| E13: Notificación + audit log post-EXECUTED | "Orden de venta ejecutada exitosamente..." (líneas finales del Then) |

---

## 12. UI y experiencia

### 12.1 Páginas / vistas afectadas

#### Página `/trade` (modificada — sin nueva página)

**Propósito:** Reutilizar la página de F09 habilitando el flujo SELL.

**Cambios respecto a F09:**

1. **Toggle BUY/SELL ambos habilitados** — quitar `disabled` del botón SELL y el tooltip "Disponible próximamente".
2. **`OrderForm` reactivo al side:**
   - Etiqueta del botón principal cambia: BUY → "Obtener quote de compra"; SELL → "Obtener quote de venta".
   - Tooltip informativo sobre el ticker dropdown: en SELL, sugerir al usuario que primero compre (link a `/portfolio` cuando exista) o que use el filtro server-side.
3. **`OrderQuotePanel` reactivo al side:**
   - BUY (F09): "Total a pagar: USD X" + "Saldo después: USD Y" (saldo decrementado).
   - SELL (F10):
     - "Producto neto a recibir: USD X" en lugar de "Total a pagar".
     - "Saldo después: USD Y" (saldo incrementado).
     - **Línea nueva**: "Posición restante: {userShares − quantity} {ticker}" o "Esta venta liquidará tu posición completa" si `userShares = quantity`.
     - Color del CTA: emerald (igual que F09).
   - Si `sufficientShares=false`: mensaje en rojo con CTA deshabilitado:
     - `userShares = 0`: "No tienes posición en {ticker}. Compra primero para poder vender."
     - `userShares > 0 && < quantity`: "Solo tienes {userShares} acciones disponibles para vender."
4. **`OrderConfirmationToast` reactivo al side:**
   - BUY: "✅ Compraste {quantity} {ticker} a USD {executionUnitPrice}" + emerald.
   - SELL: "✅ Vendiste {quantity} {ticker} a USD {executionUnitPrice} — recibiste USD {executionTotal}" + emerald.
   - `PENDING` heredado D29: "⏳ Tu orden se encoló — verás el crédito al ejecutarse" + ámbar (mismo que F09 BUY PENDING).

#### Página `/portfolio` (HU-F16, Día 8)

F10 NO crea esta página. Misma decisión MVP-friendly que F09: tras venta exitosa, el toast se muestra inline y el usuario puede operar otra orden inmediata. Cuando HU-F16 entre, F10 puede actualizar el target del CTA "Ver portafolio" del toast.

### 12.2 Componentes nuevos a crear

**Ninguno.** F10 reutiliza los 4 componentes de F09 (`TradePage`, `OrderForm`, `TickerDropdown`, `OrderQuotePanel`, `OrderConfirmationToast`).

### 12.3 Componentes modificados

| Componente | Cambios |
|---|---|
| `OrderForm.tsx` | Quitar `disabled` del toggle SELL. Mantener tooltip informativo en SELL si dropdown no está filtrado por posiciones. |
| `OrderQuotePanel.tsx` | Branching por `side`: wording, color, línea "Posición restante", manejo de `sufficientShares=false`. |
| `OrderConfirmationToast.tsx` | Branching por `side` en el wording del toast. |
| `TickerDropdown.tsx` | **Sin cambios obligatorios** en MVP — el dropdown sigue mostrando los 25. **Opcional / D-UI-2:** si el tiempo permite, filtrar por posiciones cuando `side=SELL` consultando `GET /portfolio/positions` (HU-F16). Deferir a HU-F16. |

### 12.4 Hooks o utilidades modificados

| Item | Cambios |
|---|---|
| `useQuote.ts` | Sin cambios — el `QuoteResponse` extendido es retro-compatible. |
| `useSubmitOrder.ts` | Sin cambios estructurales — agregar manejo de los 2 códigos nuevos (`SHORT_SELLING_NOT_ALLOWED`, `INSUFFICIENT_SHARES`) en el `onError`. |

### 12.5 Tipos API frontend

`src/types/api.ts` extiende `QuoteResponse` con los 2 campos nuevos:

```typescript
export interface QuoteResponse {
  // ... campos heredados F09 ...
  estimatedTotal: string;  // side-aware: BUY = subtotal+commission, SELL = subtotal-commission
  sufficientShares: boolean;  // NUEVO
  userShares: number;         // NUEVO
}
```

`OrderResponse` sin cambios estructurales (semántica side-aware documentada en JSDoc).

### 12.6 Cambios de routing

**Ninguno.** `/trade` reutilizada.

### 12.7 Mensajes de error en `messages.es.ts`

```typescript
// Códigos nuevos en F10:
SHORT_SELLING_NOT_ALLOWED: "No tienes posición en {ticker}. BloomTrade no permite ventas en corto.",
INSUFFICIENT_SHARES: "Solo tienes {available} {ticker} disponibles para vender (solicitaste {requested}).",

// Códigos heredados de F09 que pueden emitirse también en SELL — sin cambios:
INVALID_TICKER, INVALID_QUANTITY, INVALID_SIDE, INVALID_CLIENT_ORDER_ID,
ALPACA_API_ERROR, ALPACA_ORDER_REJECTED, MARKET_DATA_UNAVAILABLE,
ACCOUNT_NOT_ACTIVE, ORDER_DUPLICATE_NOT_AN_ERROR

// Código F09 que YA NO se emite desde backend (queda en frontend para retro-compatibilidad / fallback):
SIDE_NOT_YET_IMPLEMENTED  // backend ya implementa SELL; nunca debería llegar al frontend
```

---

## 13. Fuera de alcance de esta spec

- **HU-F15 Cancelar orden** — relevante principalmente para SELL encoladas (PENDING+alpacaOrderId). Stretch goal post-MVP.
- **HU-F14 Encolar orden si mercado cerrado** — el comportamiento "encolada" emerge vía D29 F09 cuando Alpaca responde `accepted`, pero la lógica explícita `MarketScheduleManager` real (no stub) queda post-MVP.
- **HU-F11/F12/F13 Limit/Stop Loss/Take Profit** — post-MVP.
- **Fractional shares en venta** — `quantity` sigue siendo INTEGER.
- **Multi-divisa** — solo USD.
- **Reconciliación automática de SELL encoladas** — cuando Alpaca filea horas/días después, BloomTrade no auto-actualiza. Deuda registrada (AGENTS #8, #13). El usuario verá la orden con `status=PENDING` en HU-F16 portfolio (cuando entre Día 8).
- **Histórico de cambios de avg_buy_price tras venta** — F10 NO modifica `avg_buy_price`. Si el usuario vende parcial y vuelve a comprar, el avg_buy_price se recalcula en F09 (upsert mezcla). Sin tabla de historial.
- **Bloqueo de ventas en mercado cerrado real** — MVP confía en `MarketScheduleManager` stub `true` + en D29 heredado para casos edge. La validación dura "no permitir SELL si mercado cerrado" queda fuera.
- **Reservar posición durante el quote** — el quote es informativo y NO bloquea la posición. Entre quote y submit, otra venta paralela puede consumir la posición y la segunda fallará con `INSUFFICIENT_SHARES`. Aceptado.
- **Wash sale prevention** — Alpaca paper puede rechazar con `wash trade detected`; BloomTrade pasa la razón al usuario sin enforce previo. Post-MVP.

---

## 14. Preguntas abiertas

> Estas son las decisiones que el SPEC **deja explícitamente para el `plan.md`**. No bloquean redacción; sí requieren resolución antes del Lote A.

1. **D-TRADING-METHOD** — ¿`TradingService.placeOrder` única (con switch por side) o métodos separados `placeOrderBuy` + `placeOrderSell`? Recomendación SPEC: **única con dispatch interno**, ya que el flujo (idempotency lock + validaciones comunes + INSERT order + Alpaca submit + post-commit listener) es 70% común. Solo la rama de validación de fondos vs posición y la actualización de balance/posición divergen. Responder en `plan.md`.

2. **D-NOTIFIER-SPLIT** — ¿`Notifier` con 6 métodos (3 BUY + 3 SELL) o 3 parametrizados por side? Recomendación SPEC: **6 métodos** (decisión §8.3). Confirmar en plan.md.

3. **D-PORTFOLIO-DECREMENT-RETURN** — ¿`decrementPosition` retorna `Optional<Position>` (vacío si se borró) o `Position` con `quantity=0` (caller decide)? Recomendación SPEC: **`Optional<Position>`** — comunica explícitamente "ya no existe" sin que el caller tenga que conocer la convención `quantity=0`. Confirmar en plan.md.

4. **D-SELL-QUEUED-RISK** — D29 F09 heredado: SELL con Alpaca `accepted` no-terminal decrementa posición optimistamente sin acreditar balance. Si Alpaca cancela después, usuario pierde posición sin crédito. ¿Aceptar como deuda MVP o pivotear a "bloquear SELL si Alpaca no responde `filled` inmediato"? Recomendación SPEC: **aceptar como deuda** (es lo coherente con F09; pivotear rompería simetría buy/sell). Documentar en plan.md como D-xx y enlazar a deudas AGENTS #8 #13.

5. **D-UI-FILTER-SELL-DROPDOWN** — ¿Filtrar `TickerDropdown` por posiciones del usuario cuando `side=SELL`? Recomendación SPEC: **NO en MVP** (requiere `GET /portfolio/positions` que es HU-F16). Server-side validation con error claro `SHORT_SELLING_NOT_ALLOWED` es suficiente UX para MVP single-user. Promoción opcional cuando F16 mergee.

6. **D-EMAIL-RENAME-F09** — ¿Renombrar las 3 templates F09 a sufijo `-buy` o dejarlas sin sufijo + crear `-sell`? Recomendación SPEC: **renombrar** (`git mv order-executed.html order-executed-buy.html`). Consistencia simétrica. Trade-off: un cambio extra en commits F10 que toca código F09 (pero git history lo refleja como rename limpio).

7. **D-AUDIT-POSITION-DELETED-FIELD** — ¿Incluir `positionDeleted: boolean` y `positionResultingQty: integer` en `ORDER_EXECUTED.details` para SELL? Recomendación SPEC: **sí ambos**. `positionDeleted` permite filtrar en Kibana "ventas que liquidaron una posición completa". `positionResultingQty` permite reconstruir el historial de posiciones desde solo audit logs.

---

## 15. Definition of Done específica de esta spec

- ☐ **NO se crea migración Flyway nueva** (verificar `\d app.orders` y `\d app.positions` ya soportan F10).
- ☐ Endpoints `POST /orders/quote` y `POST /orders` actualizados en Swagger UI con: campos `sufficientShares`/`userShares` en `QuoteResponse`, descripción side-aware de `estimatedTotal`/`executionTotal`, códigos `SHORT_SELLING_NOT_ALLOWED` y `INSUFFICIENT_SHARES`.
- ☐ `TradingService.placeOrder` con rama `side=SELL` implementada (decisión D-TRADING-METHOD aplicada).
- ☐ 2 excepciones nuevas: `ShortSellingNotAllowedException`, `InsufficientSharesException`. Mapeadas en `GlobalExceptionHandler` a 409 con códigos respectivos.
- ☐ `PortfolioService.credit(userId, amount)` implementado con lock pessimistic sobre `app.user_balances` (análogo a `debit`).
- ☐ `PortfolioService.decrementPosition(userId, ticker, sellQuantity)` implementado: SELECT FOR UPDATE → si NULL/qty=0 lanza `ShortSellingNotAllowedException`, si qty < sellQuantity lanza `InsufficientSharesException`, si OK decrementa, si qty resultante = 0 DELETE de la fila. Retorna `Optional<Position>`.
- ☐ `AlpacaTradingAdapter.submitMarketOrder` verificado que envía `side="sell"` correctamente cuando `OrderSide.SELL` (test unitario assert sobre el body enviado).
- ☐ `Notifier` extendido con 3 métodos `*Sell` y `MailNotifier` los implementa.
- ☐ 3 templates Thymeleaf nuevas en `backend/src/main/resources/templates/`: `order-executed-sell.html`, `order-rejected-sell.html`, `order-failed-sell.html` (inline-CSS estilo F09).
- ☐ Las 3 templates F09 renombradas a sufijo `-buy` (D-EMAIL-RENAME-F09).
- ☐ `OrderEventListener` extendido para distinguir `side` y llamar al método `Notifier` correcto.
- ☐ `ValidationMessages` +2 códigos (`SHORT_SELLING_NOT_ALLOWED`, `INSUFFICIENT_SHARES`).
- ☐ `ORDER_EXECUTED.details` incluye `positionDeleted` y `positionResultingQty` para SELL.
- ☐ Lock pessimistic correcto: orden de adquisición consistente (primero `app.user_balances`, después `app.positions`, o documentar la elegida) para evitar deadlocks entre BUY y SELL concurrentes del mismo usuario. Test IT específico.
- ☐ `BigDecimal` usado en `credit` y en cálculo de producto neto. Grep verifica ausencia de `double`/`float`.
- ☐ Frontend:
  - ☐ `OrderForm` con SELL habilitado (sin `disabled`).
  - ☐ `OrderQuotePanel` con wording side-aware + línea "Posición restante".
  - ☐ `OrderConfirmationToast` con wording side-aware.
  - ☐ `useSubmitOrder` con manejo de los 2 códigos nuevos.
  - ☐ `QuoteResponse` types extendido con `sufficientShares`/`userShares`.
  - ☐ `messages.es.ts` +2 códigos.
- ☐ Tests unitarios: `TradingServiceTest` (≥6 escenarios nuevos: quote SELL OK, quote SELL sin posición, quote SELL cantidad insuficiente, placeOrder SELL OK, placeOrder SELL → DELETE position, placeOrder SELL concurrencia).
- ☐ Tests de integración con WireMock: happy path SELL, Alpaca down SELL, Alpaca rechaza SELL, SHORT_SELLING_NOT_ALLOWED, INSUFFICIENT_SHARES, idempotencia SELL, concurrencia × 2 SELL del mismo ticker.
- ☐ Demo E2E manual: usuario con posición existente (de F09 demo) → /trade → toggle SELL → ticker AAPL → cantidad ≤ posición → quote → confirmar → toast emerald "Vendiste — recibiste USD X" → MailHog tiene email + Kibana tiene eventos + Alpaca dashboard refleja la venta + posición decrementada (o fila borrada si venta total).
- ☐ APRENDIZAJES.md sección "Día 7 — HU-F10" en primera persona con reflexiones.
- ☐ AGENTS.md handoff actualizado con cierre F10 + Decisiones D-xx emergentes.
- ☐ `mvn verify` verde sobre `feat/HU-F10-orden-venta-market`.
- ☐ `npm run build` verde.
- ☐ Sin cambios necesarios en STACK.md (ningún dep nuevo) ni ARCHITECTURE.md (sin módulos nuevos).

---

## Changelog

| Versión | Fecha | Cambio | Razón |
|---|---|---|---|
| 1.0 | 2026-05-23 | Versión inicial | Primer SPEC post-HU-F09 con andamio completo ya en `main`. F10 es validación retrospectiva del diseño F09: completa el flujo de trading bidireccional reutilizando ~60% del código y agregando 2 validaciones nuevas (`SHORT_SELLING_NOT_ALLOWED`, `INSUFFICIENT_SHARES`), operaciones inversas sobre `app.positions` (decrement + DELETE en qty=0) y `app.user_balances` (credit), templates email separadas por side, y habilitación del toggle SELL en frontend. Decisiones cerradas pre-redacción: (1) DELETE de fila cuando qty=0, (2) short selling bloqueado, (3) reutilizar `POST /orders` con `side=SELL`, (4) templates Thymeleaf separadas. 7 decisiones diferidas a `plan.md` (D-TRADING-METHOD, D-NOTIFIER-SPLIT, D-PORTFOLIO-DECREMENT-RETURN, D-SELL-QUEUED-RISK, D-UI-FILTER-SELL-DROPDOWN, D-EMAIL-RENAME-F09, D-AUDIT-POSITION-DELETED-FIELD). |
