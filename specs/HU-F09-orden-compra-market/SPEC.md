# spec.md — Orden de compra Market

---

## 1. Metadatos

| Campo | Valor |
|---|---|
| ID(s) de HU | HU-F09 (BT-13 en Jira) |
| Sprint | 2 |
| Prioridad MoSCoW | Must |
| Estado | Ready |
| Autor | Juan |
| Fecha creación | 2026-05-22 |
| Última actualización | 2026-05-22 |
| Versión spec | 1.1 |
| Día estimado del ROADMAP | Día 6 |

---

## 2. Historia(s) de usuario

### HU-F09 — Realizar orden de compra Market

**Como** inversionista, **quiero** colocar una orden de compra de tipo Market sobre uno de los 25 activos disponibles, **para** adquirir acciones al mejor precio disponible en el mercado, conociendo de antemano la comisión y el total exacto que se descontará de mi saldo.

### Resumen del alcance

Esta spec cubre el **ciclo de vida completo de una orden de compra Market**:

1. **Quote previo (obligatorio)** — el frontend obtiene precio actual + comisión + total estimado para que el usuario vea exactamente cuánto se le descontará antes de confirmar (ARCH §9 "La comisión se informa al usuario ANTES de confirmar la orden").
2. **Confirmación** — el usuario envía la orden con un `clientOrderId` (UUID generado en frontend para idempotencia).
3. **Validaciones server-side** — autenticación, ticker válido (lista de 25), cantidad entera positiva, fondos suficientes, mercado abierto (stub para MVP).
4. **Ejecución** — la orden se persiste en estado `PENDING`, se envía a Alpaca paper trading vía `AlpacaAdapter`, y se actualiza al estado final (`EXECUTED` / `REJECTED` / `FAILED`).
5. **Efectos transaccionales atomicos en caso de éxito** — débito del balance, upsert de la posición (`app.positions`), notificación al usuario por su canal preferido, evento `ORDER_EXECUTED` a AuditService.
6. **Idempotencia** — un mismo `clientOrderId` enviado dos veces devuelve la misma orden, no crea una segunda.

> **Sobre HU-F10 (venta Market):** queda fuera de esta spec — se implementa el Día 7 con su propio SPEC. Los modelos de datos (`app.orders`, `app.positions`) y la mayor parte de la infraestructura (`AlpacaAdapter`, `CommissionManager`, `MarketScheduleManager`) introducidos aquí se reutilizan en F10. Esto es intencional: F09 paga el costo arquitectónico del módulo Trading + Portfolio + IntegrationService.Alpaca + Admin.

---

## 3. Contexto y dependencias

### Por qué importa

Es la HU más compleja del MVP. Toca **seis módulos** del catálogo arquitectónico (TradingService, PortfolioService, IntegrationService, AdminService, NotificationService, AuditService) y materializa el corazón funcional del producto. Es la primera vez que el sistema:

- Mueve dinero del balance del usuario (operación financiera transaccional con `BigDecimal` — CLAUDE.md regla #9).
- Crea una entidad de dominio multi-estado (`Order` con su FSM) con trazabilidad explícita.
- Integra una API externa que **muta estado** del usuario (Alpaca crea órdenes reales en el paper trading account compartido) — distinto a Stripe donde la mutación queda en Stripe.
- Introduce `MarketDataAdapter` para Alpaca Market Data (consumido aquí parcialmente; HU-F18 lo extenderá con cache + multi-ticker). Polygon.io quedó diferido a post-MVP (D9 D-MD-PROVIDER).

Materializa las tácticas Bass: TAC-R1 (concurrencia — pool de threads en TradingService), TAC-R3 (priorizar eventos — `PriorityQueue` con órdenes en nivel Alta), TAC-D2 (Retry hacia Alpaca trading y Alpaca Data), TAC-I1 (Orquestar — `OrderOrchestrator` post-ejecución), TAC-M1 + TAC-I2 (AlpacaAdapter, MarketDataAdapter), TAC-S4 (audit log).

### Dependencias técnicas

- **HU-F01 Registro** — el usuario debe existir y tener `app.user_balances` con saldo inicial USD 10,000.
- **HU-F02 + HU-F03** — el usuario debe estar autenticado vía JWT (filter ya en el codebase).
- **HU-F04 + HU-F20** — `notificationChannel` del usuario determina cómo se le notifica la ejecución (default email → MailHog).
- **Migraciones V1–V4** ya aplicadas. Esta spec introduce **V5**.
- **`Notifier` interface** (HU-F02/F06) ya existe — solo extensión con métodos nuevos.
- **`Auditor` interface** (HU-F01) ya existe — solo +event types nuevos.
- **`BalanceInitializer`** ya existe — esta spec NO lo toca; solo lee/escribe `app.user_balances`.

### Variables de entorno nuevas

| Variable | Propósito | Ejemplo |
|---|---|---|
| `ALPACA_API_KEY` | Credencial del paper trading account compartido (header `APCA-API-KEY-ID`) | `PK_TEST_...` |
| `ALPACA_API_SECRET` | Secret del paper trading account (header `APCA-API-SECRET-KEY`) | (64 chars) |
| `ALPACA_BASE_URL` | Endpoint REST de la trading API. **Sin `/v2` al final** (D28). | `https://paper-api.alpaca.markets` |
| `ALPACA_DATA_BASE_URL` | Endpoint REST de la market data API (D9 D-MD-PROVIDER reemplaza Polygon en MVP). | `https://data.alpaca.markets` |
| `TRADING_DEFAULT_COMMISSION_PCT` | % comisión default si la BD no tiene fila (fallback) | `0.02` |

### Features que dependen de esta

- **HU-F10 Venta Market** (Día 7) — reutiliza `app.orders`, `app.positions`, `AlpacaAdapter`, `CommissionManager`.
- **HU-F16 Consultar portafolio** (Día 8) — lee de `app.positions` y `app.user_balances`.
- **HU-F21 Consultar saldo** (Día 8) — lee de `app.user_balances`.
- **HU-F18 Dashboard de acciones** (Día 9) — usa `MarketDataAdapter` (introducido aquí parcialmente).
- **HU-F14 Encolar orden si mercado cerrado** (post-MVP) — extiende `MarketScheduleManager` (stub en este SPEC).
- **HU-F15 Cancelar orden** (stretch goal) — extiende `app.orders` con estados de cancelación.

---

## 4. Actores y precondiciones

### Actores involucrados

| Actor | Rol del sistema | Participación |
|---|---|---|
| Usuario autenticado | INVESTOR | Iniciador del quote y de la confirmación |
| Sistema BloomTrade | — | Validador, persistente, orquestador |
| Alpaca Markets | Externo | Ejecutor de la orden (paper trading) |
| Alpaca Market Data | Externo | Proveedor de precio actual del ticker (mismas credenciales que Alpaca trading) |
| MailHog (dev) | Externo | Receptor de notificaciones email |
| ElasticSearch | Externo | Receptor de audit events |

### Precondiciones del sistema

- Usuario tiene sesión JWT activa con `rol = INVESTOR` y `estado = ACTIVE`.
- `app.user_balances` tiene fila para el usuario (creada en HU-F01).
- Migración Flyway V5 aplicada (`app.orders`, `app.positions`, `config.commission_rates`).
- Variables de entorno Alpaca (trading + data) configuradas.
- Alpaca paper trading account compartido disponible (curse account; el balance del paper account es separado del `app.user_balances` interno — ver §8.5).
- Alpaca Market Data disponible (incluido en la cuenta paper; ~200 req/min — suficiente para MVP con 1 usuario testing).

### Datos requeridos en el sistema

- `config.commission_rates` con al menos una fila `(rol=INVESTOR, percentage=0.02)` cargada como seed.
- Lista de 25 tickers permitidos disponible en `auth.profile.catalog.AllowedTickers` (ya existe — se reutiliza la misma constante).

---

## 5. Flujos

### 5.1 Flujo principal — quote + confirmación + ejecución exitosa

#### Paso 1: Usuario ve el formulario de orden

1. Usuario autenticado navega a `/trade` (o `/trade/buy?ticker=AAPL` desde un link directo).
2. Frontend renderiza el formulario `OrderForm` con dropdown de los 25 tickers + input de cantidad (entero positivo).
3. Usuario selecciona ticker `AAPL` e ingresa cantidad `10`.

#### Paso 2: Quote previo (informativo, no compromete recursos)

4. Frontend envía `POST /api/v1/orders/quote` con body `{ ticker: "AAPL", side: "BUY", quantity: 10 }`.
5. `JwtAuthenticationFilter` valida el JWT y pobla `SecurityContextHolder` con `AuthenticatedUser`.
6. `OrderController` resuelve `userId` desde el principal — **nunca desde body/query/path**.
7. `TradingService.quote(userId, request)`:
   - Valida `ticker` ∈ los 25 permitidos (lanza `INVALID_TICKER` si no).
   - Valida `quantity` > 0 y ≤ `MAX_QUANTITY_PER_ORDER` (default 10,000 — configurable).
   - Invoca `MarketDataAdapter.getLatestPrice(ticker)` → `BigDecimal` (Alpaca Data latest-quote endpoint).
   - Invoca `CommissionManager.calculate(userRole=INVESTOR, total=price×quantity)` → `BigDecimal` redondeado a 2 decimales.
   - Calcula `totalCost = (price × quantity) + commission`.
   - Consulta `app.user_balances.balance` del usuario.
   - Computa `sufficientFunds = balance ≥ totalCost`.
   - Invoca `MarketScheduleManager.isOpenNow(ticker)` — en MVP siempre devuelve `true` (stub).
8. `TradingService` retorna `QuoteResponse` y `OrderController` responde 200 con:

   ```json
   {
     "ticker": "AAPL",
     "side": "BUY",
     "quantity": 10,
     "estimatedUnitPrice": "184.50",
     "estimatedSubtotal": "1845.00",
     "commission": "36.90",
     "estimatedTotal": "1881.90",
     "currency": "USD",
     "userBalance": "10000.00",
     "sufficientFunds": true,
     "marketOpen": true,
     "quotedAt": "2026-05-22T14:32:18Z"
   }
   ```

9. Frontend muestra al usuario:
   - "Precio actual estimado: USD 184.50 / acción"
   - "Subtotal: USD 1845.00"
   - "Comisión (2%): USD 36.90"
   - "**Total a descontar: USD 1881.90**"
   - "Saldo después: USD 8118.10"
   - Botón **"Confirmar compra"** habilitado si `sufficientFunds=true && marketOpen=true`.

> **Importante:** el `estimatedTotal` es **informativo**. El precio de ejecución real de una Market Order puede diferir ligeramente (slippage). El backend NO bloquea la ejecución por slippage en MVP — ver §10.2 y decisión pendiente Dxx en plan.md.

#### Paso 3: Confirmación → ejecución

10. Usuario presiona "Confirmar compra".
11. Frontend genera `clientOrderId = crypto.randomUUID()` y envía `POST /api/v1/orders` con body:

    ```json
    {
      "clientOrderId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
      "ticker": "AAPL",
      "side": "BUY",
      "type": "MARKET",
      "quantity": 10
    }
    ```

12. `OrderController` resuelve `userId` desde el principal.
13. `TradingService.placeOrder(userId, request)` abre **una transacción JPA**:
    - Verifica que NO exista fila en `app.orders` con `client_order_id = ...` (idempotencia secundaria — ver §5.3.7).
    - Repite las validaciones del quote (ticker permitido, cantidad válida, mercado abierto). Si alguna falla → excepción + rollback.
    - Re-fetch del precio vía `MarketDataAdapter` (precio puede haber cambiado desde el quote).
    - Re-calcula `commission` y `totalCost`.
    - **Re-valida fondos** contra `app.user_balances.balance` (lock pessimistic via `SELECT FOR UPDATE`).
    - Si `balance < totalCost` → lanza `InsufficientFundsException` + rollback.
    - INSERT en `app.orders`:
      ```
      id=UUID, user_id, client_order_id, ticker, side='BUY', type='MARKET',
      quantity=10, quoted_unit_price=184.50, quoted_commission=36.90,
      quoted_total=1881.90, status='PENDING', submitted_at=NOW()
      ```
    - Invoca `AlpacaAdapter.submitMarketOrder(...)`:
      - Header `APCA-API-KEY-ID` + `APCA-API-SECRET-KEY`.
      - Body: `{ symbol: "AAPL", qty: 10, side: "buy", type: "market", time_in_force: "day", client_order_id: "f47ac10b-..." }`.
      - Envuelta en `@Retry(name="alpacaApi")` (Resilience4j: 3 intentos a 1s, 3s, 5s — ARCH §15 TAC-D2).
    - Alpaca responde con `{ id: "alp_xxx", status: "filled" | "accepted" | "rejected", filled_avg_price: "184.62", filled_qty: "10", ... }` (síncrono para market orders en paper trading; típicamente <500ms).
14. `OrderOrchestrator` (en la misma transacción) procesa la respuesta de Alpaca:
    - Si `status="filled"` (o `accepted` y inmediatamente filled, según API):
      - UPDATE `app.orders` SET `status='EXECUTED'`, `alpaca_order_id`, `execution_unit_price` (= `filled_avg_price`), `execution_total` (= `filled_avg_price × quantity + commission`), `executed_at=NOW()`.
      - UPDATE `app.user_balances` SET `balance = balance - execution_total`, `updated_at=NOW()`. (El lock pessimistic ya está tomado.)
      - UPSERT `app.positions` (`user_id + ticker` UNIQUE):
        - Si no existe fila: INSERT con `quantity=10`, `avg_buy_price=184.62`.
        - Si ya existe: UPDATE con `quantity_new = quantity_old + 10`, `avg_buy_price_new = ((quantity_old × avg_buy_price_old) + (10 × 184.62)) / quantity_new`.
    - Si `status="rejected"`:
      - UPDATE `app.orders` SET `status='REJECTED'`, `error_code='ALPACA_ORDER_REJECTED'`, `error_message=...`.
      - NO se descuenta saldo, NO se actualiza `app.positions`.
    - COMMIT.
15. **Post-commit** (fuera de la transacción, dispatched async):
    - `Notifier.notifyOrderExecuted(userId, order)` → email "Tu orden de compra de 10 AAPL se ejecutó a USD 184.62 / acción" usando el `notificationChannel` del usuario (default email → MailHog).
    - `Auditor.emit(ORDER_CREATED)` + `Auditor.emit(ORDER_EXECUTED)` a ElasticSearch.
16. Backend responde 201 Created con:

    ```json
    {
      "id": "ord_uuid",
      "clientOrderId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
      "ticker": "AAPL",
      "side": "BUY",
      "type": "MARKET",
      "quantity": 10,
      "quotedUnitPrice": "184.50",
      "executionUnitPrice": "184.62",
      "commission": "36.90",
      "executionTotal": "1883.10",
      "status": "EXECUTED",
      "submittedAt": "2026-05-22T14:32:25Z",
      "executedAt": "2026-05-22T14:32:25Z"
    }
    ```

17. Frontend redirige a `/portfolio` (o muestra confirmación inline) con el nuevo balance + posición visible.

**Postcondiciones del flujo principal:**

- Fila nueva en `app.orders` con `status='EXECUTED'`.
- `app.user_balances.balance` decrementado en `execution_total`.
- Fila en `app.positions` (nueva o actualizada) con cantidad acumulada y `avg_buy_price` recalculado.
- Email "Orden ejecutada" en MailHog.
- Eventos en ElasticSearch: `ORDER_CREATED`, `ORDER_EXECUTED`.
- Alpaca paper account tiene la posición real (verificable en dashboard de Alpaca).
- `alpaca_order_id` persistido en la fila para reconciliación futura.

### 5.2 Flujos alternativos

#### 5.2.1 Quote sin saldo suficiente (informativo, no bloqueante)

**Cuándo se activa:** `POST /orders/quote` cuando `balance < estimatedTotal`.

**Comportamiento:**

- El endpoint responde 200 normalmente con `sufficientFunds: false`.
- El frontend deshabilita el botón "Confirmar compra" y muestra "Saldo insuficiente. Necesitas USD X.XX adicionales."
- NO se considera error — el quote es siempre informativo.

#### 5.2.2 Quote con mercado cerrado (MVP: stub siempre devuelve `marketOpen=true`)

**Cuándo se activará en post-MVP:** `MarketScheduleManager.isOpenNow(ticker)` devuelve `false` cuando el mercado destino del ticker está cerrado.

**Comportamiento MVP:** El stub siempre devuelve `true`. Esta rama no se ejecuta. La estructura está prevista para HU-F14.

**Comportamiento post-MVP (referencia):** quote responde con `marketOpen=false`; frontend muestra "Mercado cerrado. La orden se encolará para la próxima apertura (HU-F14)."

#### 5.2.3 Orden con `clientOrderId` duplicado (idempotencia)

**Cuándo se activa:** Frontend envía el mismo `clientOrderId` dos veces (doble-click, reintento por timeout, navegador buggy).

**Comportamiento:**

1. `OrderController` recibe la segunda request.
2. `TradingService.placeOrder` consulta `app.orders` por `client_order_id`.
3. Si encuentra una fila existente: NO crea orden nueva, NO llama a Alpaca, NO modifica balance.
4. Backend responde 200 OK (no 201 — para distinguir del caso "nueva orden") con el estado actual de la orden existente.
5. Emite `ORDER_DUPLICATE_REQUEST` a AuditService (severidad INFO).

> **Razón:** el frontend no debería tener forma de saber que su request "se perdió" — si recibe el mismo objeto Order que la primera vez (status EXECUTED, mismo id, mismo executionUnitPrice), el comportamiento desde su perspectiva es indistinguible de "la primera request completó normalmente". Esto es el comportamiento canónico de idempotencia REST.

### 5.3 Flujos de error

#### 5.3.1 Ticker inválido (no en lista de 25)

**Cuándo se dispara:** Body de `/orders/quote` o `/orders` contiene `ticker` no presente en `AllowedTickers`.

**Respuesta:** HTTP 400 con código `INVALID_TICKER` y `message: "El ticker {X} no está habilitado para operar."`.

**Estado final:** Sin cambios. No se persiste orden, no se consulta precio.

**Evento de auditoría:** No se emite (no es interesante forense — el validator lo rechaza temprano).

#### 5.3.2 Cantidad inválida

**Cuándo se dispara:** `quantity ≤ 0`, no entero, o `> MAX_QUANTITY_PER_ORDER` (10,000 default).

**Respuesta:** HTTP 400 con código `INVALID_QUANTITY`.

**Estado final:** Sin cambios.

#### 5.3.3 Side inválido (para futuro F10; en F09 solo `BUY` válido)

**Cuándo se dispara:** Body contiene `side` distinto a `"BUY"`.

**Respuesta:** HTTP 400 con código `INVALID_SIDE`.

> **Nota:** el endpoint `/orders` acepta `side=BUY` y `side=SELL`. F09 implementa el handler de `BUY`. F10 (Día 7) agrega el handler de `SELL`. Hasta entonces, `side=SELL` retorna 400 con `SIDE_NOT_YET_IMPLEMENTED`.

#### 5.3.4 Fondos insuficientes (al ejecutar)

**Cuándo se dispara:** `POST /orders` cuando `balance < totalCost` en la verificación dentro de la transacción (con `SELECT FOR UPDATE`).

**Respuesta:** HTTP 409 Conflict con código `INSUFFICIENT_FUNDS` y `details: { balance: "1000.00", required: "1881.90", shortfall: "881.90" }`.

**Estado final:** Rollback completo de la transacción. La fila tentativa en `app.orders` (si llegó a INSERT) se elimina. No se llama a Alpaca.

**Evento de auditoría:** `ORDER_REJECTED` con `details: { reason: "INSUFFICIENT_FUNDS", required, balance }`.

#### 5.3.5 Alpaca API caída (post-retries)

**Cuándo se dispara:** Las 3 llamadas a Alpaca (1s, 3s, 5s — TAC-D2) fallan (timeout, 5xx, network error).

**Respuesta:** HTTP 502 Bad Gateway con código `ALPACA_API_ERROR` y `details: { lastError: "...", attempts: 3 }`.

**Estado final:** La fila en `app.orders` queda con `status='FAILED'`, `error_code='ALPACA_API_ERROR'`. **El saldo NO se descuenta** porque la actualización del balance solo ocurre tras respuesta exitosa de Alpaca (mismo `@Transactional` rollbackea).

**Evento de auditoría:** `ORDER_FAILED` con `details: { reason: "ALPACA_API_ERROR", lastError }`.

**Notificación:** Email "Tu orden no se pudo procesar — Alpaca no respondió. Intenta nuevamente en unos minutos."

> **Trade-off:** si Alpaca **responde éxito** pero el commit local falla (BD caída justo después), tendremos inconsistencia (orden ejecutada en Alpaca, no registrada en BloomTrade). Esto **NO se mitiga en MVP** — queda como deuda registrada en plan.md (D17 reconciliación nocturna post-MVP). El `client_order_id` enviado a Alpaca permite reconciliación manual.

#### 5.3.6 Alpaca rechaza la orden explícitamente

**Cuándo se dispara:** Alpaca responde con `status="rejected"` o 4xx con razón específica (ej: símbolo no soportado por Alpaca paper, mercado cerrado en su lado, qty inválida según sus reglas).

**Respuesta:** HTTP 422 Unprocessable Entity con código `ALPACA_ORDER_REJECTED` y `details: { alpacaReason: "...", alpacaCode: "..." }`.

**Estado final:** Fila en `app.orders` con `status='REJECTED'`. Saldo intacto.

**Evento de auditoría:** `ORDER_REJECTED` con `details: { reason: "ALPACA_REJECTED", alpacaReason }`.

**Notificación:** Email "Tu orden fue rechazada por el mercado: {razón}. Intenta con un ticker distinto."

#### 5.3.7 `clientOrderId` faltante o malformado

**Cuándo se dispara:** Body de `/orders` no incluye `clientOrderId` o el valor no es UUID v4 válido.

**Respuesta:** HTTP 400 con código `VALIDATION_REQUIRED` (si null/vacío) o `INVALID_CLIENT_ORDER_ID` (si formato inválido).

**Estado final:** Sin cambios.

#### 5.3.8 Market data (Alpaca) caída al pedir quote

**Cuándo se dispara:** Las 3 llamadas a Alpaca Data (1s, 3s, 5s) fallan.

**Respuesta de `/orders/quote`:** HTTP 502 con código `MARKET_DATA_UNAVAILABLE` y `message: "No se pudo obtener el precio actual de {ticker}. Intenta nuevamente."`.

**Respuesta de `/orders`:** Mismo error. Sin orden creada.

**Evento de auditoría:** `QUOTE_FAILED` con `details: { ticker, reason: "ALPACA_DATA_API_ERROR" }`.

> **Degradación post-MVP (TAC-D4):** si HU-F18 introduce el `PriceCache`, en caída de Alpaca Data podemos devolver el último precio cacheado (≤30s old) con flag `stale=true`. MVP: sin cache aún, fallar duro.

#### 5.3.9 Usuario no autenticado

**Cuándo se dispara:** Request a `/orders` o `/orders/quote` sin JWT válido.

**Respuesta:** HTTP 401 con código `AUTHENTICATION_REQUIRED` (manejado por `JwtAuthenticationFilter` existente — no se llega al controller).

**Estado final:** Sin cambios.

#### 5.3.10 Usuario con `estado != ACTIVE`

**Cuándo se dispara:** El JWT es válido pero `app.users.estado` del usuario es `BLOCKED` o `SUSPENDED`.

**Respuesta:** HTTP 403 con código `ACCOUNT_NOT_ACTIVE`.

**Evento de auditoría:** `ORDER_BLOCKED_BY_ACCOUNT_STATUS`.

#### 5.3.11 Concurrencia: dos órdenes simultáneas del mismo usuario que juntas exceden el saldo

**Cuándo se dispara:** El usuario hace doble-click MUY rápido (frontend no debounce-d) o tiene dos pestañas confirmando órdenes simultáneamente.

**Comportamiento:** Una transacción toma el lock `SELECT FOR UPDATE` sobre `app.user_balances`; la otra espera. Cuando la primera commitea, la segunda re-evalúa el saldo y posiblemente falla con `INSUFFICIENT_FUNDS` (§5.3.4).

> **Invariante BD:** el `CHECK (balance >= 0)` de V2 garantiza que nunca se persistirá un saldo negativo, incluso si la lógica de validación tuviera un bug. Es defensa en profundidad — un INSERT que viola esta constraint hace fallar la transacción completa.

---

## 6. Contratos de datos

### 6.1 Endpoints nuevos

#### 6.1.1 `POST /api/v1/orders/quote`

**Propósito:** Devolver un quote informativo (precio + comisión + total + estado del saldo) ANTES de que el usuario confirme la orden. No persiste nada. No descuenta. No llama a Alpaca.

**Auth requerido:** Sí

```yaml
paths:
  /api/v1/orders/quote:
    post:
      summary: Obtener quote informativo de una orden (precio + comisión + total)
      tags: [Orders]
      security:
        - bearerAuth: []
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [ticker, side, quantity]
              properties:
                ticker:
                  type: string
                  description: Símbolo de la acción. Debe estar en la lista de 25 permitidos.
                  example: "AAPL"
                side:
                  type: string
                  enum: [BUY, SELL]
                  description: SELL retorna 400 SIDE_NOT_YET_IMPLEMENTED hasta HU-F10.
                  example: "BUY"
                quantity:
                  type: integer
                  minimum: 1
                  maximum: 10000
                  example: 10
      responses:
        '200':
          description: Quote calculado
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/QuoteResponse'
        '400':
          description: Ticker, side o quantity inválidos
        '401':
          description: No autenticado
        '403':
          description: Cuenta no activa
        '502':
          description: Market data (Alpaca) no disponible

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
        - marketOpen
        - quotedAt
      properties:
        ticker: { type: string, example: "AAPL" }
        side: { type: string, enum: [BUY, SELL], example: "BUY" }
        quantity: { type: integer, example: 10 }
        estimatedUnitPrice:
          type: string
          description: BigDecimal serializado como string (precisión preservada). Precio actual del ticker según Alpaca Market Data.
          example: "184.50"
        estimatedSubtotal:
          type: string
          example: "1845.00"
        commission:
          type: string
          example: "36.90"
          description: 2% del subtotal redondeado a 2 decimales (HALF_UP).
        estimatedTotal:
          type: string
          example: "1881.90"
        currency:
          type: string
          example: "USD"
        userBalance:
          type: string
          example: "10000.00"
        sufficientFunds:
          type: boolean
          example: true
        marketOpen:
          type: boolean
          example: true
          description: En MVP siempre true (stub). HU-F14 lo hidratará con lógica real.
        quotedAt:
          type: string
          format: date-time
          example: "2026-05-22T14:32:18Z"
```

#### 6.1.2 `POST /api/v1/orders`

**Propósito:** Crear y ejecutar una orden de compra Market. Endpoint principal de F09. Es idempotente por `clientOrderId`.

**Auth requerido:** Sí

```yaml
paths:
  /api/v1/orders:
    post:
      summary: Crear y ejecutar una orden (Market Order)
      tags: [Orders]
      security:
        - bearerAuth: []
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [clientOrderId, ticker, side, type, quantity]
              properties:
                clientOrderId:
                  type: string
                  format: uuid
                  description: UUID v4 generado por el frontend. Permite idempotencia — la misma request enviada N veces produce 1 orden.
                  example: "f47ac10b-58cc-4372-a567-0e02b2c3d479"
                ticker:
                  type: string
                  example: "AAPL"
                side:
                  type: string
                  enum: [BUY, SELL]
                  example: "BUY"
                type:
                  type: string
                  enum: [MARKET]
                  description: Limit/Stop/TakeProfit son post-MVP. Solo MARKET.
                  example: "MARKET"
                quantity:
                  type: integer
                  minimum: 1
                  maximum: 10000
                  example: 10
      responses:
        '201':
          description: Orden creada y ejecutada (nueva).
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/OrderResponse'
        '200':
          description: Orden ya existente con ese clientOrderId (idempotencia). Body idéntico al de 201.
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/OrderResponse'
        '400':
          description: Validation error (ticker/side/quantity/clientOrderId inválidos)
        '401':
          description: No autenticado
        '403':
          description: Cuenta no activa
        '409':
          description: Saldo insuficiente
        '422':
          description: Alpaca rechazó la orden
        '502':
          description: Alpaca (trading o market data) no disponible

components:
  schemas:
    OrderResponse:
      type: object
      required:
        - id
        - clientOrderId
        - ticker
        - side
        - type
        - quantity
        - quotedUnitPrice
        - commission
        - status
        - submittedAt
      properties:
        id:
          type: string
          format: uuid
        clientOrderId:
          type: string
          format: uuid
        ticker:
          type: string
        side:
          type: string
          enum: [BUY, SELL]
        type:
          type: string
          enum: [MARKET]
        quantity:
          type: integer
        quotedUnitPrice:
          type: string
          description: Precio que se mostró en el quote (referencial, NO el de ejecución).
        executionUnitPrice:
          type: string
          nullable: true
          description: Precio real de ejecución reportado por Alpaca (filled_avg_price). Null si status != EXECUTED.
        commission:
          type: string
        quotedTotal:
          type: string
          nullable: true
          description: Total estimado en el momento del submit (basado en quotedUnitPrice).
        executionTotal:
          type: string
          nullable: true
          description: Total real cobrado (executionUnitPrice × quantity + commission). Null si no se ejecutó.
        status:
          type: string
          enum: [PENDING, EXECUTED, REJECTED, FAILED]
        alpacaOrderId:
          type: string
          nullable: true
        errorCode:
          type: string
          nullable: true
          description: Solo presente si status ∈ {REJECTED, FAILED}.
        errorMessage:
          type: string
          nullable: true
        submittedAt:
          type: string
          format: date-time
        executedAt:
          type: string
          format: date-time
          nullable: true
```

> **Datos sensibles ocultos:** la respuesta NUNCA incluye credenciales de Alpaca, ni el balance del paper account compartido, ni IDs internos de la cuenta Alpaca (más allá del `alpacaOrderId` que es seguro exponer).

### 6.2 Endpoints modificados

Ninguno en HU-F09. (HU-F16 / F21 introducirán `GET /portfolio` y `GET /balance` para consultar el estado resultante.)

### 6.3 Esquemas de datos compartidos

- `QuoteResponse`, `OrderResponse` introducidos aquí.
- `OrderStatus` enum (PENDING, EXECUTED, REJECTED, FAILED) usado por F09 y F10.

---

## 7. Cambios en base de datos

### 7.1 Migración a crear

**Archivo:** `backend/src/main/resources/db/migration/V5__trading_orders_positions_commissions.sql`

**Schemas afectados:** `app` (orders, positions), `config` (commission_rates).

### 7.2 Nuevas tablas

#### `app.orders`

```sql
CREATE TABLE app.orders (
    id                       UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                  UUID         NOT NULL REFERENCES app.users(id) ON DELETE RESTRICT,
    client_order_id          UUID         NOT NULL,
    ticker                   VARCHAR(10)  NOT NULL,
    side                     VARCHAR(4)   NOT NULL,
    type                     VARCHAR(10)  NOT NULL,
    quantity                 INTEGER      NOT NULL,
    quoted_unit_price        NUMERIC(19, 4) NOT NULL,
    quoted_commission        NUMERIC(19, 4) NOT NULL,
    quoted_total             NUMERIC(19, 4) NOT NULL,
    execution_unit_price     NUMERIC(19, 4),
    execution_total          NUMERIC(19, 4),
    status                   VARCHAR(10)  NOT NULL DEFAULT 'PENDING',
    alpaca_order_id          VARCHAR(80),
    error_code               VARCHAR(40),
    error_message            TEXT,
    submitted_at             TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    executed_at              TIMESTAMPTZ,
    CONSTRAINT chk_order_side     CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT chk_order_type     CHECK (type IN ('MARKET')),
    CONSTRAINT chk_order_status   CHECK (status IN ('PENDING', 'EXECUTED', 'REJECTED', 'FAILED')),
    CONSTRAINT chk_order_quantity CHECK (quantity > 0),
    CONSTRAINT uq_orders_client_order_id UNIQUE (client_order_id)
);

CREATE INDEX idx_orders_user_id        ON app.orders (user_id);
CREATE INDEX idx_orders_status         ON app.orders (status);
CREATE INDEX idx_orders_ticker         ON app.orders (ticker);
CREATE INDEX idx_orders_submitted_at   ON app.orders (submitted_at DESC);
CREATE UNIQUE INDEX idx_orders_alpaca_order_id
    ON app.orders (alpaca_order_id) WHERE alpaca_order_id IS NOT NULL;
```

**Justificación de decisiones:**

- `NUMERIC(19, 4)`: 4 decimales porque el precio de Alpaca Market Data viene típicamente con 2-3 decimales, y queremos margen para el `avg_buy_price` que es resultado de divisiones. Comisión calculada se redondea a 2 al persistirla (HALF_UP).
- `ON DELETE RESTRICT` en `user_id`: no permitir borrar un usuario que tiene órdenes históricas (cumplimiento auditoría — los registros financieros se preservan).
- `client_order_id UNIQUE`: corazón de la idempotencia (§5.2.3).
- `alpaca_order_id UNIQUE WHERE NOT NULL`: defensa contra reportar dos veces la misma orden ejecutada en Alpaca.
- `INTEGER` para `quantity`: en MVP no soportamos fractional shares (post-MVP si lo necesitamos pasamos a `NUMERIC(19,4)`).
- Status enum estrechado a 4 (`PENDING, EXECUTED, REJECTED, FAILED`) — el catálogo de ARCH §9 (Pendiente, Enviada, En Ejecución, Ejecutada, Cancelada, Rechazada, Expirada, En Revisión, Fallida, Detenida) es para post-MVP; MVP usa solo los 4 estrictamente necesarios. **Decisión documentada en plan.md.**

#### `app.positions`

```sql
CREATE TABLE app.positions (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID         NOT NULL REFERENCES app.users(id) ON DELETE RESTRICT,
    ticker          VARCHAR(10)  NOT NULL,
    quantity        INTEGER      NOT NULL,
    avg_buy_price   NUMERIC(19, 4) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_position_quantity CHECK (quantity >= 0),
    CONSTRAINT uq_positions_user_ticker UNIQUE (user_id, ticker)
);

CREATE INDEX idx_positions_user_id ON app.positions (user_id);
```

**Justificación:**

- `UNIQUE (user_id, ticker)`: invariante crítico — un usuario tiene **una sola fila** por ticker. Las compras incrementan `quantity` y recalculan `avg_buy_price`; las ventas (F10) decrementan.
- `CHECK (quantity >= 0)`: defensa contra venta excesiva en F10 (si llega aquí, algo falló antes — no debería ocurrir).
- Fila con `quantity = 0` puede quedar tras vender todo en F10 (decisión de diseño F10 — para F09 esto no aplica). Alternativa F10: DELETE de la fila. Documentar en SPEC de F10.

#### `config.commission_rates`

```sql
CREATE TABLE config.commission_rates (
    id              UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    role            VARCHAR(20)    NOT NULL,
    percentage      NUMERIC(7, 4)  NOT NULL,
    valid_from      TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_to        TIMESTAMPTZ,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_commission_role
        CHECK (role IN ('INVESTOR', 'BROKER', 'BROKER_USER')),
    CONSTRAINT chk_commission_pct
        CHECK (percentage >= 0 AND percentage <= 1)
);

CREATE UNIQUE INDEX uq_commission_active_per_role
    ON config.commission_rates (role)
    WHERE valid_to IS NULL;

-- Seed: 2% INVESTOR commission
INSERT INTO config.commission_rates (role, percentage)
    VALUES ('INVESTOR', 0.0200);
```

**Justificación:**

- Schema `config` (no `app`): TAC-M2 (diferir el enlace mediante configuración). El administrador puede cambiar la comisión en runtime (HU-F30 post-MVP) y el sistema la respeta sin redeploy.
- `NUMERIC(7,4)`: rango `0.0000` a `1.0000` (0% a 100%). 4 decimales para precisión.
- Historial preservado: cuando se cambia la tasa, la fila antigua se cierra con `valid_to=NOW()` y se crea una nueva con `valid_from=NOW()`, `valid_to=NULL`. El partial unique index garantiza que solo UNA fila por rol esté "activa" (sin valid_to).
- Seed inicial: 2% INVESTOR (ARCH §9 default).

### 7.3 Modificaciones a tablas existentes

Ninguna. `app.users` y `app.user_balances` se reutilizan tal como están en V2.

### 7.4 Datos semilla

- 1 fila en `config.commission_rates` (`INVESTOR, 0.02`) — incluida en la migración V5 (no es seed separado).

---

## 8. Mapeo arquitectónico

### 8.1 Módulos involucrados

| Módulo | Rol | Componentes específicos tocados |
|---|---|---|
| TradingService | Iniciador + orquestador | `OrderController`, `TradingService`, `OrderOrchestrator`, `OrderRepository`, paquete `trading/*` (nuevo) |
| PortfolioService | Notificado (escritura) | `PortfolioService` (servicio + métodos `debit(...)`, `upsertPosition(...)`), `PositionRepository` (nuevo), `UserBalance` (extensión de métodos de dominio) |
| IntegrationService | Intermediario | `AlpacaAdapter` (nuevo, paquete `integration/alpaca/`), `MarketDataAdapter` (nuevo, paquete `integration/alpaca/`) |
| AdminService | Proveedor de configuración | `ConfigurationManager` (nuevo, lee de `config.commission_rates`), `CommissionManager` (nuevo, consume ConfigurationManager), `MarketScheduleManager` (nuevo, stub MVP) |
| NotificationService | Notificado (despacho async) | `Notifier` (extensión: `notifyOrderExecuted`, `notifyOrderFailed`), templates Thymeleaf nuevos |
| AuditService | Notificado (registro) | `Auditor` (consumido — ya existe), 6 event types nuevos (ver §9.1) |

> **Decisión arquitectónica clave:** los 6 módulos ya existen en el catálogo de ARCH §3, pero hasta HU-F09 solo Auth y Audit estaban materializados como paquetes Java. F09 introduce el código de los otros 4 — es la HU que **construye la mayor parte del andamio arquitectónico restante del MVP**. F10/F16/F18/F21 reutilizan lo que F09 monta.

### 8.2 Interfaces consumidas

| Mecanismo / Bean | Módulo que lo expone | Para qué se usa aquí |
|---|---|---|
| `JwtAuthenticationFilter` + `SecurityContextHolder` | AuthService (HU-F02-F03) | Validar JWT + resolver `userId` desde el principal |
| `User` (entity), `UserRepository` | AuthService | Verificar `estado=ACTIVE` |
| `Auditor` | AuditService (HU-F01 D1, sin prefijo `I`) | Emitir 6 event types nuevos (§9.1) |
| `Notifier` | NotificationService (HU-F02/F06) | Disparar emails `order-executed` y `order-failed` |
| `UserBalance`, `UserBalanceRepository` | PortfolioService (HU-F01) | Lectura con lock pessimistic + actualización del balance |
| `AllowedTickers` (catalog) | AuthService.profile (HU-F04) | Validar ticker ∈ los 25 permitidos. **Reutilizado**, no duplicado. |

### 8.3 Interfaces expuestas

| Interfaz | Quién la consumirá | Contrato |
|---|---|---|
| `TradingService` (clase, no interfaz nominal en MVP) | `OrderController`, futuros endpoints internos | Métodos `quote(userId, request): QuoteResponse`, `placeOrder(userId, request): OrderResponse` |
| `PortfolioService` (clase) | F09 (este SPEC), F10, F16, F21 | Métodos `debit(userId, amount)`, `upsertPosition(userId, ticker, qty, unitPrice)`, `getBalance(userId)`, `getPositions(userId)` |
| `CommissionManager` (clase) | TradingService | Método `calculate(role, subtotal): BigDecimal` |
| `MarketScheduleManager` (clase) | TradingService | Método `isOpenNow(ticker): boolean` — stub en MVP |
| `MarketDataAdapter` (clase) | TradingService (aquí), DashboardService (HU-F18) | Método `getLatestPrice(ticker): BigDecimal` |
| `AlpacaAdapter` (clase) | TradingService | Método `submitMarketOrder(SubmitMarketOrderCommand): AlpacaOrderResult` |

> **Nota D1 (sin prefijo `I`):** Las interfaces inter-módulo se nombran sin prefijo `I` por decisión locked HU-F01 D1. En F09, varios "servicios" no son interfaces Java formales sino clases concretas inyectadas por tipo. Esto es **deliberadamente más simple** que crear una interfaz nominal por cada servicio cuando hay una sola implementación. Si en post-MVP necesitamos múltiples implementaciones (ej: dos adapters de market data), se extrae la interfaz en un PR separado.

### 8.4 Tácticas de Bass aplicadas

| Táctica | ID | Cómo se materializa en esta feature |
|---|---|---|
| Usar un intermediario | TAC-M1 | `AlpacaAdapter` y `MarketDataAdapter` son los únicos puntos de contacto con Alpaca (trading y data). Cambiar de proveedor de market data en post-MVP (p. ej. Polygon.io) es un cambio aislado. |
| Adaptar la interfaz | TAC-I2 | `AlpacaAdapter` traduce conceptos de Alpaca (asset, qty, side, time_in_force) ↔ conceptos de dominio (Order, Ticker, Quantity, Side). `MarketDataAdapter` traduce el latest-quote endpoint de Alpaca Data ↔ `LatestPrice(ticker, price, asOf)`. |
| Mantener registro de auditoría | TAC-S4 | 6 event types nuevos capturando creación, ejecución, rechazo, falla, idempotencia y bloqueo por cuenta inactiva. |
| Encapsular | TAC-M3 | `CommissionManager` encapsula el cálculo (TradingService no sabe del schema `config`). `OrderOrchestrator` encapsula la secuencia post-ejecución (debit → upsert position → notify → audit). |
| Retry | TAC-D2 | `@Retry(name="alpacaApi")` y `@Retry(name="alpacaDataApi")` configurados en `application.yml` con 3 intentos a 1s/3s/5s. |
| Diferir el enlace mediante configuración | TAC-M2 | `config.commission_rates` permite cambiar la tasa en runtime sin redeploy (HU-F30 lo expondrá en UI; HU-F09 solo lee). |
| Orquestar | TAC-I1 | `OrderOrchestrator` coordina la secuencia post-Alpaca: actualizar portafolio → enviar notificación → registrar auditoría (ARCH §4 TradingService). |
| Autorizar actores | TAC-S2 | El controller exige `rol=INVESTOR` (vía Spring Security `@PreAuthorize` o equivalente). Bloqueo si `estado != ACTIVE`. |

> **Sobre TAC-R1 (concurrencia) y TAC-R3 (priorizar eventos):** ARCH §4 menciona `PriorityQueue` + `ThreadPool` en TradingService. **MVP: NO materializamos estos componentes**. La justificación: con un solo usuario testing localmente, la ventaja de priorización es teórica. La complejidad de implementar bien una cola con priorización + thread pool + manejo de back-pressure + integración con `@Transactional` no se justifica para MVP. **Decisión D-CONC documentada en plan.md.** Post-MVP (cuando se aproxime al escenario ESC-R1 de 1500 órdenes simultáneas), se introduce `@Async` + `CompletableFuture` o `Spring Batch` según necesidad.

### 8.5 Modelo de fondos: BloomTrade vs Alpaca paper

Punto crítico que **DEBE entenderse antes de implementar**:

- **`app.user_balances`** es el saldo **interno de BloomTrade** del usuario (USD 10,000 inicial, otorgado al registrarse).
- **El paper trading account de Alpaca** tiene su **propio saldo** (típicamente USD 100,000 paper money), compartido entre todos los usuarios del MVP demo.
- **No hay sincronización entre los dos.** Cuando un usuario compra 10 AAPL:
  - BloomTrade descuenta de `app.user_balances` el precio + comisión.
  - Alpaca descuenta de su paper account el precio (sin comisión — Alpaca no cobra comisión en paper).
  - Las acciones quedan en el paper account de Alpaca.
  - BloomTrade refleja la posición en `app.positions`.

**Consecuencia importante:** si dos usuarios compran 10 AAPL cada uno:
- BloomTrade tiene 2 filas en `app.positions` (una por usuario).
- Alpaca paper tiene **20 AAPL en su única cuenta**.
- `app.positions` es la fuente de verdad para "qué tiene cada usuario en BloomTrade".
- Alpaca paper es la fuente de verdad para "qué está físicamente comprado".

**Implicación:** las ventas (F10) descontarán de `app.positions` localmente y de Alpaca paper globalmente. Si un usuario "vende" 5 AAPL en BloomTrade, Alpaca paper vende 5 (de su pool global). Si dos usuarios venden al mismo tiempo más de lo que Alpaca paper tiene, **uno fallará** — pero esto es protección post-MVP (reconciliación). En MVP con 1 usuario testing, no aplica.

> Esta decisión se hereda del MVP (STACK.md §7.1): "cuenta de paper trading compartida en demo". Documentado como **decisión simplificadora del MVP** que se debe revisar antes de producción real.

---

## 9. Efectos colaterales

### 9.1 Eventos de auditoría

| `event_type` | Trigger | Campos extra en `details` |
|---|---|---|
| `ORDER_CREATED` | INSERT en `app.orders` (status=PENDING) | `{ orderId, clientOrderId, ticker, side, type, quantity, quotedTotal }` |
| `ORDER_EXECUTED` | UPDATE a `status=EXECUTED` post-Alpaca | `{ orderId, alpacaOrderId, executionUnitPrice, executionTotal, commission }` |
| `ORDER_REJECTED` | UPDATE a `status=REJECTED` (fondos, Alpaca rechazo, ticker no soportado) | `{ orderId, reason, details }` |
| `ORDER_FAILED` | UPDATE a `status=FAILED` (Alpaca down post-retries, Alpaca Data down) | `{ orderId, reason, lastError, attempts }` |
| `ORDER_DUPLICATE_REQUEST` | clientOrderId ya existe | `{ existingOrderId, clientOrderId }` |
| `ORDER_BLOCKED_BY_ACCOUNT_STATUS` | Usuario con `estado ∈ {BLOCKED, SUSPENDED}` intenta operar | `{ accountState }` |
| `QUOTE_FAILED` | Alpaca Data down al pedir quote | `{ ticker, reason, lastError }` |

### 9.2 Notificaciones

Todas se envían vía el `notificationChannel` configurado por el usuario en HU-F20 (default EMAIL → MailHog).

| Trigger | Plantilla | Contenido resumido |
|---|---|---|
| `ORDER_EXECUTED` | `order-executed-buy.html` | "Tu orden de compra de {quantity} {ticker} se ejecutó a USD {executionUnitPrice}. Total descontado: USD {executionTotal} (incluye comisión USD {commission}). Tu saldo actual: USD {newBalance}." |
| `ORDER_REJECTED` (Alpaca) | `order-rejected-buy.html` | "Tu orden de compra de {quantity} {ticker} fue rechazada por el mercado: {alpacaReason}. No se realizó ningún cargo." |
| `ORDER_FAILED` (Alpaca trading/data down) | `order-failed-buy.html` | "Tu orden de compra de {quantity} {ticker} no pudo procesarse por un error técnico. Intenta nuevamente en unos minutos. Tu saldo no fue afectado." |

> **Decisión:** las notificaciones de `ORDER_REJECTED` por `INSUFFICIENT_FUNDS` **no envían email**. La razón: el usuario ya vio el error en pantalla en el momento (response 409). Email sería ruido. Solo enviar email cuando el flujo es asíncrono o cuando hay desconexión espacio-temporal entre la causa y la consecuencia (Alpaca rechaza ≠ fondos insuficientes — el primero llega después de submit, el segundo bloquea el submit).

### 9.3 Cambios en caché Redis

No aplica en HU-F09. (HU-F18 introducirá `PriceCache` para los 25 tickers con TTL 30s; HU-F09 consulta Alpaca Data directamente para cada quote/orden.)

### 9.4 Llamadas a APIs externas

| API externa | Método (operación) | Adapter | Cuándo se invoca |
|---|---|---|---|
| Alpaca Market Data | `GET /v2/stocks/{ticker}/quotes/latest` | `MarketDataAdapter` | Cada `POST /orders/quote` y cada `POST /orders` (re-fetch fresco antes de ejecutar) |
| Alpaca Markets (paper) | `POST /v2/orders` | `AlpacaAdapter` | Cada `POST /orders` exitoso (post-validaciones) |

**RetryPolicy:**

- `alpacaDataApi`: 3 reintentos a 1s, 3s, 5s. Si las 3 fallan, propaga `MarketDataUnavailableException`.
- `alpacaApi`: 3 reintentos a 1s, 3s, 5s. Si las 3 fallan, la orden queda en `status=FAILED`.

**Idempotency hacia Alpaca:** el `client_order_id` se envía en el body — Alpaca lo respeta como key de deduplicación nativa.

---

## 10. Atributos de calidad aplicables

### 10.1 Escenarios de calidad referenciados

| ID escenario | Atributo | Cómo esta feature lo soporta |
|---|---|---|
| ESC-R1 | Rendimiento | "1500 órdenes simultáneas → cada orden confirmada en <5s". **NO se cumple en MVP** sin TAC-R1+TAC-R3 (cola+pool). Documentado como deuda; la prueba JMeter del Día 10 (ROADMAP §5) medirá el comportamiento real y registrará el gap. |
| ESC-I1 | Interoperabilidad | "Confirmación de ejecución de Alpaca → portafolio actualizado + notificación en <5s". Se cumple porque la transacción JPA del flujo principal corre síncrono (típicamente <500ms total). |
| ESC-S2 | Seguridad | "Inversionista accede a portafolio no asignado → denegado en <1s". Se cumple porque el `userId` se resuelve del JWT y nunca del body — un usuario no puede ordenar para otro. |
| ESC-S3 | Seguridad | "Orden con sesión robada → rechazada, fondos intactos". El JWT vence en 15min. Sin refresh activo en MVP (D23 cross-HU). |

### 10.2 Constraints específicos de esta feature

| Constraint | Medida | Cómo se verifica |
|---|---|---|
| `POST /orders/quote` responde en <2s p95 (Alpaca Data free tier) | 20 requests serializadas en localhost | `time curl` o JMeter mini |
| `POST /orders` responde en <5s p95 incluyendo Alpaca paper | 20 requests serializadas con saldo suficiente | `time curl` o JMeter |
| `BigDecimal` usado para todo monto financiero (CLAUDE.md regla #9) | Inspección de código | Grep por `double`/`float` en módulo trading/portfolio — debe estar vacío para monetario |
| Comisión calculada con `HALF_UP` y 2 decimales | Test unitario | Cases: 1000.001 × 0.02 = 20.00 (no 20.0000200) |
| `app.user_balances.balance` NUNCA negativo | CHECK constraint BD + lock `SELECT FOR UPDATE` | Test IT: dos transacciones simultáneas que juntas exceden el saldo — una debe fallar con CHECK violation o `INSUFFICIENT_FUNDS` |
| Idempotencia por `client_order_id`: la misma request N veces produce 1 fila | Test IT con N=10 | Assert: `SELECT COUNT(*) FROM orders WHERE client_order_id = ?` retorna 1; las 9 últimas requests devuelven 200 (no 201) con el mismo `id` |
| `app.positions.quantity` NUNCA negativo | CHECK constraint BD | Test IT: protección en F10, no aplica directo a F09 (solo INSERT/UPDATE incrementando) |
| `OrderController` NUNCA acepta `userId` por path/body/query | Inspección + test | Endpoint `?userId=other-uuid` debe ignorarlo (resolver desde principal) |
| Datos sensibles no expuestos | Inspección JSON respuesta | Sin `alpacaApiKey`, sin balance del paper account global, sin internal IDs de Alpaca account |
| Rollback completo si Alpaca rechaza explícitamente | Test IT con WireMock | Assert: order fila tiene `status=REJECTED`, `app.user_balances.balance` no se modificó, `app.positions` no tiene fila nueva |
| Rollback completo si Alpaca down post-retries | Test IT con WireMock devolviendo 503 | Mismas aserciones que arriba |
| RetryPolicy: exactamente 3 intentos antes de fallar | Test IT con WireMock contando llamadas | Assert: WireMock recibió 3 invocaciones (no más, no menos) |
| Saldo + comisión funciona con valores en bordes (0.01, 99999.99) | Test parametrizado | Casos límite |
| Concurrencia: dos órdenes simultáneas que juntas exceden saldo → exactamente UNA falla | Test IT con CompletableFuture × 2 | Assert: un EXECUTED, un INSUFFICIENT_FUNDS |

---

## 11. Criterios de aceptación

### 11.1 Escenarios de aceptación

```gherkin
Funcionalidad: Orden de compra Market

  Antecedentes:
    Dado un usuario autenticado "juan@example.com" con saldo USD 10000.00 y rol INVESTOR
    Y la lista de tickers permitidos incluye AAPL, MSFT, JPM, GOOGL, TSLA (entre los 25)
    Y la tasa de comisión INVESTOR está en 0.02 (2%) en config.commission_rates
    Y MarketScheduleManager.isOpenNow(*) devuelve true (stub MVP)
    Y Alpaca paper trading account tiene buying_power > 1,000,000
    Y Alpaca Data devuelve precio 184.50 para AAPL al consultar el latest-quote

  Escenario: Quote informativo de compra con saldo suficiente
    Cuando el usuario envía POST /api/v1/orders/quote con { ticker: "AAPL", side: "BUY", quantity: 10 }
    Entonces el sistema responde 200 con
      | campo                | valor      |
      | estimatedUnitPrice   | "184.50"   |
      | estimatedSubtotal    | "1845.00"  |
      | commission           | "36.90"    |
      | estimatedTotal       | "1881.90"  |
      | userBalance          | "10000.00" |
      | sufficientFunds      | true       |
      | marketOpen           | true       |
    Y NO se persiste ninguna orden en app.orders
    Y NO se llama a Alpaca

  Escenario: Quote con saldo insuficiente
    Dado que el usuario tiene saldo 100.00 (en lugar de 10000.00)
    Cuando envía POST /orders/quote con { ticker: "AAPL", side: "BUY", quantity: 10 }
    Entonces el sistema responde 200 con sufficientFunds=false
    Y el frontend deshabilita el botón "Confirmar"

  Escenario: Orden de compra ejecutada exitosamente
    Cuando el usuario envía POST /api/v1/orders con
      | clientOrderId    | f47ac10b-58cc-4372-a567-0e02b2c3d479 |
      | ticker           | AAPL                                  |
      | side             | BUY                                   |
      | type             | MARKET                                |
      | quantity         | 10                                    |
    Y Alpaca responde { id: "alp_1", status: "filled", filled_avg_price: "184.62", filled_qty: "10" }
    Entonces el sistema responde 201 con status="EXECUTED"
    Y app.orders tiene fila con status=EXECUTED, execution_unit_price=184.62, execution_total=1883.10
    Y app.user_balances.balance pasa de 10000.00 a 8116.90
    Y app.positions tiene fila { ticker: "AAPL", quantity: 10, avg_buy_price: 184.62 }
    Y se emite ORDER_CREATED y ORDER_EXECUTED a ElasticSearch
    Y MailHog recibe email "Tu orden de compra de 10 AAPL se ejecutó a USD 184.62"

  Escenario: Compra adicional del mismo ticker actualiza avg_buy_price
    Dado que el usuario ya tiene posición { ticker: "AAPL", quantity: 10, avg_buy_price: 184.62 }
    Cuando ejecuta una segunda compra de 10 AAPL ejecutada a 190.00
    Entonces app.positions tiene { ticker: "AAPL", quantity: 20, avg_buy_price: 187.31 }
    Donde 187.31 = ((10 × 184.62) + (10 × 190.00)) / 20

  Escenario: Orden con clientOrderId duplicado retorna la orden existente
    Dado que ya existe una orden con clientOrderId="f47ac10b-..." y status="EXECUTED"
    Cuando el usuario envía POST /api/v1/orders con el mismo clientOrderId
    Entonces el sistema responde 200 (no 201) con la orden existente
    Y NO se crea fila nueva en app.orders
    Y NO se llama a Alpaca
    Y NO se descuenta saldo
    Y se emite ORDER_DUPLICATE_REQUEST a auditoría

  Escenario: Saldo insuficiente al ejecutar
    Dado que el usuario tiene saldo 100.00
    Cuando envía POST /api/v1/orders para comprar 10 AAPL (total ~1881.90)
    Entonces el sistema responde 409 con código INSUFFICIENT_FUNDS
    Y app.orders NO tiene fila nueva con ese clientOrderId
    Y app.user_balances.balance permanece 100.00
    Y se emite ORDER_REJECTED con reason="INSUFFICIENT_FUNDS"

  Escenario: Alpaca caída post-retries
    Dado que Alpaca devuelve 503 a las 3 llamadas (1s, 3s, 5s)
    Cuando el usuario envía POST /api/v1/orders válido
    Entonces el sistema responde 502 con código ALPACA_API_ERROR
    Y app.orders tiene fila con status="FAILED", error_code="ALPACA_API_ERROR"
    Y app.user_balances NO se modificó
    Y se envía email "Tu orden no pudo procesarse — Alpaca no respondió"

  Escenario: Alpaca rechaza la orden
    Dado que Alpaca responde { status: "rejected", reject_reason: "qty exceeds buying power" }
    Cuando el usuario envía POST /api/v1/orders válido
    Entonces el sistema responde 422 con código ALPACA_ORDER_REJECTED
    Y app.orders tiene status="REJECTED", error_code="ALPACA_ORDER_REJECTED"
    Y app.user_balances NO se modificó

  Escenario: Alpaca Data caído al pedir quote
    Dado que Alpaca Data devuelve 503 a las 3 llamadas
    Cuando el usuario envía POST /api/v1/orders/quote
    Entonces el sistema responde 502 con código MARKET_DATA_UNAVAILABLE

  Escenario: Ticker no permitido
    Cuando el usuario envía POST /api/v1/orders/quote con ticker="GME"
    Entonces el sistema responde 400 con código INVALID_TICKER

  Esquema del escenario: Validación de quantity
    Cuando se envía POST /api/v1/orders con { quantity: <valor> }
    Entonces el sistema responde <httpStatus> con código <errorCode>

    Ejemplos:
      | valor   | httpStatus | errorCode          |
      | 1       | 201        | (none)             |
      | 10000   | 201        | (none)             |
      | 0       | 400        | INVALID_QUANTITY   |
      | -5      | 400        | INVALID_QUANTITY   |
      | 10001   | 400        | INVALID_QUANTITY   |
      | null    | 400        | VALIDATION_REQUIRED|

  Escenario: Usuario con cuenta SUSPENDED
    Dado que app.users.estado = "SUSPENDED" para el usuario
    Cuando envía POST /orders válido
    Entonces el sistema responde 403 con código ACCOUNT_NOT_ACTIVE
    Y se emite ORDER_BLOCKED_BY_ACCOUNT_STATUS

  Escenario: Concurrencia — dos órdenes simultáneas que juntas exceden saldo
    Dado que el usuario tiene saldo 2000.00 (suficiente para UNA compra de 10 AAPL @ 184.50 + comisión, no para dos)
    Cuando se envían POST /orders simultáneamente con clientOrderIds distintos
    Entonces exactamente UNA orden queda EXECUTED
    Y la otra responde 409 INSUFFICIENT_FUNDS
    Y app.user_balances.balance refleja el descuento de la única ejecutada
```

### 11.2 Trazabilidad criterios → escenarios

| Criterio de aceptación HU-F09 | Escenario Gherkin que lo cubre |
|---|---|
| E1: Usuario obtiene quote con precio + comisión + total ANTES de confirmar | "Quote informativo de compra con saldo suficiente" |
| E2: Usuario ejecuta compra exitosa con descuento de fondos y actualización de portafolio | "Orden de compra ejecutada exitosamente" |
| E3: Compra adicional del mismo ticker actualiza avg_buy_price correctamente | "Compra adicional del mismo ticker actualiza avg_buy_price" |
| E4: Idempotencia por clientOrderId | "Orden con clientOrderId duplicado retorna la orden existente" |
| E5: Saldo insuficiente rechaza la orden sin descuento | "Saldo insuficiente al ejecutar" |
| E6: Alpaca down causa FAILED sin descuento de saldo | "Alpaca caída post-retries" |
| E7: Alpaca rechaza explícitamente → REJECTED sin descuento | "Alpaca rechaza la orden" |
| E8: Alpaca Data down → quote falla con error claro | "Alpaca Data caído al pedir quote" |
| E9: Validación de ticker, quantity, side | "Ticker no permitido", "Validación de quantity" |
| E10: Cuenta no activa → bloqueada | "Usuario con cuenta SUSPENDED" |
| E11: Concurrencia preserva invariante de saldo no-negativo | "Concurrencia — dos órdenes simultáneas que juntas exceden saldo" |
| E12: Comisión informada al usuario ANTES de confirmar (ARCH §9) | "Quote informativo de compra con saldo suficiente" (campos `commission` y `estimatedTotal` en la respuesta) |
| E13: Notificación + audit log se generan tras EXECUTED | "Orden de compra ejecutada exitosamente" (líneas finales del Then) |

---

## 12. UI y experiencia

### 12.1 Páginas / vistas afectadas

#### Página `/trade` (o `/trade/buy`)

**Propósito:** Formulario único para colocar una orden Market. En F09 solo `side=BUY` está habilitado; F10 reutilizará agregando toggle BUY/SELL.

**Acceso:** Protegida — solo usuarios autenticados con `rol=INVESTOR`.

**Componente principal:** `TradePage.tsx` que renderiza `OrderForm`.

**Comportamiento:**

1. Al cargar, frontend hace `GET /api/v1/me` (HU-F04) para obtener nombre + tickerPreferences + balance (futuro: HU-F21).
2. `OrderForm` muestra:
   - Dropdown de los 25 tickers, agrupado por mercado (NYSE, NASDAQ, LSE, TSE, ASX).
   - Input numérico `quantity` (entero positivo).
   - Toggle `BUY` (preseleccionado, único habilitado en F09) / `SELL` (deshabilitado con tooltip "Disponible próximamente").
   - Botón "Obtener quote" — habilitado cuando ticker + quantity son válidos.
3. Al presionar "Obtener quote":
   - Frontend muestra spinner mientras hace `POST /orders/quote`.
   - Al recibir 200: renderiza `OrderQuotePanel` con la tabla de precio + comisión + total + saldo después.
   - Al recibir 502 (Alpaca Data down): muestra "No se pudo obtener el precio. Intenta de nuevo." y resetea el form.
4. Si `sufficientFunds=true && marketOpen=true`, el botón "Confirmar compra" se habilita.
5. Al presionar "Confirmar compra":
   - Frontend genera `clientOrderId = crypto.randomUUID()`.
   - Hace `POST /orders` con el body.
   - **Mientras espera**: el botón se deshabilita + muestra spinner. **No** permitir nuevo click.
   - Al recibir 201 (o 200 idempotente): redirige a `/portfolio` con un toast "✅ Orden ejecutada: 10 AAPL a USD 184.62".
   - Al recibir 409 INSUFFICIENT_FUNDS: muestra inline "Tu saldo cambió. Saldo actual: USD X. Vuelve a pedir quote."
   - Al recibir 422 ALPACA_ORDER_REJECTED: muestra "El mercado rechazó tu orden: {alpacaReason}. Intenta con otro ticker."
   - Al recibir 502 ALPACA_API_ERROR: muestra "Alpaca no respondió. Tu saldo está intacto. Intenta en unos minutos."

#### Página `/portfolio` (introducida en HU-F16, Día 8)

F09 NO crea esta página. F09 redirige a `/portfolio` post-ejecución pero la página puede ser placeholder en este punto del MVP. Alternativa MVP: redirigir a `/trade` con un toast de éxito y resetear el form para permitir otra compra.

> **Decisión MVP-friendly:** `TradePage` muestra el toast de éxito y permite **otra orden inmediata** sin redirección. Cuando HU-F16 entre, redirige a `/portfolio`. Documentar como decisión D-UI-1 en plan.md.

### 12.2 Componentes nuevos a crear

| Componente | Ubicación | Propósito |
|---|---|---|
| `TradePage` | `src/pages/TradePage.tsx` | Página principal `/trade` que orquesta el flujo |
| `OrderForm` | `src/features/trading/components/OrderForm.tsx` | Form con ticker dropdown + quantity input + side toggle |
| `TickerDropdown` | `src/features/trading/components/TickerDropdown.tsx` | Dropdown agrupado por mercado, alimentado de la constante `tickers.ts` |
| `OrderQuotePanel` | `src/features/trading/components/OrderQuotePanel.tsx` | Tabla de quote con precio/comisión/total + botón confirmar |
| `OrderConfirmationToast` | `src/features/trading/components/OrderConfirmationToast.tsx` | Toast de éxito con detalles de la orden ejecutada |

### 12.3 Hooks o utilidades nuevas

| Item | Ubicación | Propósito |
|---|---|---|
| `useQuote` | `src/features/trading/hooks/useQuote.ts` | Mutation: POST /orders/quote, retorna `QuoteResponse` |
| `useSubmitOrder` | `src/features/trading/hooks/useSubmitOrder.ts` | Mutation: POST /orders. Genera `clientOrderId` internamente. Maneja idempotencia retorno 200 vs 201. |
| `formatBigDecimal` | `src/shared/utils/formatBigDecimal.ts` | Convierte string "184.50" a "USD 184.50" para display |

### 12.4 Tipos API frontend

`src/types/api.ts` se extiende con:

```typescript
export type OrderSide = "BUY" | "SELL";
export type OrderType = "MARKET";
export type OrderStatus = "PENDING" | "EXECUTED" | "REJECTED" | "FAILED";

export interface QuoteRequest {
  ticker: string;
  side: OrderSide;
  quantity: number;
}

export interface QuoteResponse {
  ticker: string;
  side: OrderSide;
  quantity: number;
  estimatedUnitPrice: string;
  estimatedSubtotal: string;
  commission: string;
  estimatedTotal: string;
  currency: string;
  userBalance: string;
  sufficientFunds: boolean;
  marketOpen: boolean;
  quotedAt: string;
}

export interface PlaceOrderRequest {
  clientOrderId: string;
  ticker: string;
  side: OrderSide;
  type: OrderType;
  quantity: number;
}

export interface OrderResponse {
  id: string;
  clientOrderId: string;
  ticker: string;
  side: OrderSide;
  type: OrderType;
  quantity: number;
  quotedUnitPrice: string;
  executionUnitPrice: string | null;
  commission: string;
  quotedTotal: string;
  executionTotal: string | null;
  status: OrderStatus;
  alpacaOrderId: string | null;
  errorCode: string | null;
  errorMessage: string | null;
  submittedAt: string;
  executedAt: string | null;
}
```

### 12.5 Cambios de routing

| Ruta | Componente | Acceso |
|---|---|---|
| `/trade` | `TradePage` | Protegida — solo `rol=INVESTOR`, `estado=ACTIVE` |

Agregar entrada "Operar" en el menú del `AppHeader` (después de "Premium").

### 12.6 Mensajes de error en `messages.es.ts`

```typescript
INVALID_TICKER: "Este ticker no está habilitado para operar.",
INVALID_QUANTITY: "La cantidad debe ser un entero positivo entre 1 y 10000.",
INVALID_SIDE: "Operación inválida.",
SIDE_NOT_YET_IMPLEMENTED: "La venta estará disponible próximamente.",
INVALID_CLIENT_ORDER_ID: "Error técnico al generar la orden. Recarga la página.",
INSUFFICIENT_FUNDS: "Saldo insuficiente. Tu saldo: USD {balance}, requerido: USD {required}.",
ALPACA_API_ERROR: "El mercado no respondió. Tu saldo está intacto. Intenta de nuevo.",
ALPACA_ORDER_REJECTED: "El mercado rechazó tu orden: {alpacaReason}.",
MARKET_DATA_UNAVAILABLE: "No se pudo obtener el precio actual. Intenta de nuevo.",
ACCOUNT_NOT_ACTIVE: "Tu cuenta no está activa. Contacta soporte.",
ORDER_DUPLICATE_NOT_AN_ERROR: "Tu orden ya estaba registrada.",
```

---

## 13. Fuera de alcance de esta spec

- **HU-F10 Orden de venta Market** — su propia spec el Día 7. Reutiliza infraestructura.
- **HU-F14 Encolar orden si mercado cerrado** — el `MarketScheduleManager` es stub MVP que siempre devuelve `true`.
- **HU-F15 Cancelar orden** — stretch goal Sprint 2; requiere extender enum `OrderStatus` con `CANCELLED`.
- **HU-F11 Limit Order, HU-F12 Stop Loss, HU-F13 Take Profit** — todas post-MVP (Sprint 3 original).
- **Fractional shares** — `quantity` es `INTEGER`. Post-MVP migrar a `NUMERIC(19,4)`.
- **Multi-divisa** — solo USD.
- **Comisión variable por broker asignado** — `config.commission_rates` lo permite (`role=BROKER`, `role=BROKER_USER`), pero HU-F09 solo aplica `INVESTOR`. HU-F05 (Comisionista, post-MVP) lo extenderá.
- **Cola de prioridad + thread pool (TAC-R1/R3)** — diferido. Ver §8.4 nota.
- **Reconciliation job nocturno** entre `app.orders` y Alpaca paper — deuda registrada para post-MVP.
- **Refresh de JWT durante orden** — si el JWT vence justo durante la transacción, el filter ya habrá pasado; la operación completa. Si vence ANTES, el filter rechaza con 401. Refresh transparente es post-MVP (HU-F0X cross-HU).
- **Histórico de transiciones de estado en BD** — los estados se persisten en `app.orders.status`; el log de transiciones va a ElasticSearch vía AuditService. Si en post-MVP se necesita query SQL de transiciones, agregar tabla `app.order_state_transitions`.
- **Notificación de "tu orden está pendiente"** — todas las órdenes Market en paper trading se ejecutan inmediatamente; el estado `PENDING` solo persiste milisegundos. Sin notificación dedicada para este estado.
- **Dashboard de precios con polling cada 30s** — HU-F18 (Día 9).
- **Visualización del portafolio resultante** — HU-F16 (Día 8).
- **Visualización del saldo en navbar** — HU-F21 (Día 8).

---

## 14. Preguntas abiertas

> **Importante:** estas son las decisiones que el SPEC **deja explícitamente para el `plan.md`**. No bloquean redacción; sí requieren resolución antes del Lote A.

1. **D-CONC** — ¿Materializar TradingService `PriorityQueue` + `ThreadPool` en MVP o diferir? Recomendación SPEC: diferir. Responder en `plan.md`.
2. **D-MD-CACHE** — ¿Introducir `PriceCache` (Redis, TTL 30s) parcialmente en HU-F09 o esperar HU-F18? Recomendación SPEC: esperar HU-F18. Para MVP single-user, el free tier de Alpaca Data es suficiente sin cache.
3. **D-SLIPPAGE** — ¿Implementar slippage tolerance (rechazar si `executionUnitPrice > quotedUnitPrice × 1.02`)? Recomendación SPEC: NO en MVP. Documentar como deuda.
4. **D-ORDER-LOG-TRANSITIONS** — ¿Tabla `app.order_state_transitions` o solo ES? Recomendación SPEC: solo ES (AuditService).
5. **D-ALPACA-CLIENT** — ¿SDK no-oficial `alpaca-java` o `RestClient` de Spring? Recomendación SPEC: `RestClient`. Razones: (a) STACK.md no lista `alpaca-java`; (b) un menos adopt-and-abandon-risk; (c) WireMock estabiliza tests sin importar SDK; (d) operaciones que necesitamos son 2-3 endpoints. Responder en `plan.md`.
6. **D-POLYGON-FREE-LIMITS** — Polygon free tier limita 5 req/min. Si el usuario hace muchos quotes rápidos, ¿cómo reaccionamos? Recomendación SPEC: dejar fallar con `MARKET_DATA_UNAVAILABLE` en MVP; HU-F18 cache lo amortigua. Documentar en plan.md.
7. **D-MAX-QTY-PER-ORDER** — `MAX_QUANTITY_PER_ORDER` ¿hardcoded a 10000 o configurable en `config`? Recomendación SPEC: hardcoded como constante en `TradingService` para MVP. Post-MVP movible a `config`.

---

## 15. Definition of Done específica de esta spec

- ☐ Migración Flyway `V5__trading_orders_positions_commissions.sql` creada y aplicada (`app.orders`, `app.positions`, `config.commission_rates` con seed INVESTOR=0.02).
- ☐ Los 2 endpoints documentados en Swagger UI (`POST /api/v1/orders/quote`, `POST /api/v1/orders`).
- ☐ `AlpacaAdapter` (`RestClient` + `@Retry(name="alpacaApi")` 3×1s/3s/5s) implementado con método `submitMarketOrder`.
- ☐ `MarketDataAdapter` (`RestClient` + `@Retry(name="polygonApi")` 3×1s/3s/5s) implementado con `getLatestPrice`.
- ☐ `CommissionManager` (lee de `config.commission_rates`, fallback a `TRADING_DEFAULT_COMMISSION_PCT` si no hay fila).
- ☐ `MarketScheduleManager` (stub MVP que siempre retorna `true`).
- ☐ `PortfolioService` con métodos `debit(userId, amount)`, `upsertPosition(userId, ticker, qty, unitPrice)`, `getBalance(userId)`.
- ☐ `OrderOrchestrator` ejecuta la secuencia post-Alpaca en orden: update order → debit balance → upsert position → notify (async) → audit (async).
- ☐ Transacción JPA envuelve INSERT order → submit Alpaca → UPDATE order → debit balance → upsert position (todo o nada).
- ☐ Lock pessimistic (`SELECT FOR UPDATE`) sobre `app.user_balances` durante validación de fondos.
- ☐ Constraint BD `CHECK (balance >= 0)` no se viola en ningún escenario (test IT).
- ☐ Idempotencia por `client_order_id` verificada: la misma request 10× produce 1 fila y 9 respuestas 200 con el mismo id.
- ☐ Comisión calculada con `BigDecimal` + `HALF_UP` a 2 decimales (test parametrizado).
- ☐ `BigDecimal` usado en TODO monto (grep verifica ausencia de `double`/`float` en módulo trading/portfolio).
- ☐ `OrderController` resuelve `userId` desde `SecurityContextHolder` — test verifica que `?userId=otro` no impacta.
- ☐ Datos sensibles NO expuestos: `alpacaApiKey`, balance global de Alpaca paper, IDs internos de la cuenta paper. Test grep en respuestas JSON.
- ☐ Notificación email para `EXECUTED`, `REJECTED` (Alpaca), `FAILED` (Alpaca/Polygon down) — 3 templates Thymeleaf inline-CSS.
- ☐ 6 event types en AuditService verificables en Kibana: `ORDER_CREATED`, `ORDER_EXECUTED`, `ORDER_REJECTED`, `ORDER_FAILED`, `ORDER_DUPLICATE_REQUEST`, `ORDER_BLOCKED_BY_ACCOUNT_STATUS`, + `QUOTE_FAILED`.
- ☐ Tests unitarios: `TradingServiceTest` (≥10 escenarios), `CommissionManagerTest`, `OrderMapperTest`.
- ☐ Tests de integración con WireMock: happy path, Alpaca down, Alpaca rechaza, Polygon down, INSUFFICIENT_FUNDS, idempotencia, concurrencia × 2.
- ☐ Frontend: `TradePage`, `OrderForm`, `TickerDropdown`, `OrderQuotePanel` rendereables.
- ☐ Hooks: `useQuote`, `useSubmitOrder` con manejo de los 5 error codes principales.
- ☐ Demo E2E manual: usuario nuevo → registra → login → /trade → AAPL × 10 → quote → confirmar → toast éxito → MailHog tiene email + Kibana tiene eventos.
- ☐ Variables de entorno documentadas en `.env.example`: `ALPACA_API_KEY`, `ALPACA_API_SECRET`, `ALPACA_BASE_URL` (sin `/v2` — D28), `ALPACA_DATA_BASE_URL`, `TRADING_DEFAULT_COMMISSION_PCT`.
- ☐ STACK.md §7.1 y §7.2 actualizados si la decisión D-ALPACA-CLIENT difiere de "RestClient nativo" (documentar SDK elegido).
- ☐ `mvn verify` verde sobre `feat/HU-F09-orden-compra-market`.
- ☐ Frontend `npm run build` verde.

---

## Changelog

| Versión | Fecha | Cambio | Razón |
|---|---|---|---|
| 1.0 | 2026-05-22 | Versión inicial | Primer SPEC del Sprint 2; la HU más compleja del MVP. Cubre 6 módulos arquitectónicos y materializa el grueso del andamio restante (TradingService, PortfolioService.positions, IntegrationService.Alpaca, IntegrationService.Polygon, AdminService.Commission/MarketSchedule). HU-F10/F16/F18/F21 dependen del andamio aquí montado. |
| 1.1 | 2026-05-22 | Consolidación post-implementación: (a) D9 D-MD-PROVIDER materializado — drop `POLYGON_*` env vars, agregar `ALPACA_DATA_BASE_URL`, todas las referencias a "Polygon" en el cuerpo quedan como historia documentada. (b) D23–D27 emergentes (Lote G) incorporadas a §5/§9 mediante notas de implementación en `plan.md` §2.4. (c) D28 emergente (Lote H): `ALPACA_BASE_URL` debe NO incluir `/v2` (el adapter lo prepende). (d) D29 emergente (Lote H.5): `accepted` no-terminal tras polling se mapea a `PENDING + alpacaOrderId` (encolada), no a `FAILED`. Email + audit `ORDER_QUEUED` separados de FAILED. | Cierre de HU-F09 con backend completo, frontend completo y HITO 8 validado. Las 5 decisiones emergentes documentadas en `plan.md` cubren los gaps entre el SPEC v1.0 (diseño) y la realidad de implementación (concurrencia, config, ventana de mercado cerrado). |
