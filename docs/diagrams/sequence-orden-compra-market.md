# Diagrama de Secuencia — Orden de Compra Market (HU-F09)

**Fuente:** `specs/HU-F09-orden-compra-market/SPEC.md` §5.1 (flujo principal).
**Última actualización:** 2026-05-25.

Representa el flujo end-to-end de una orden de compra Market exitosa: desde que el usuario abre `/trade` hasta que el portafolio queda actualizado y la confirmación llega al frontend. Cubre las dos llamadas HTTP (`POST /quote` informativo y `POST /orders` transaccional) y la cadena post-commit (notificación + auditoría).

---

## Fase 1 — Quote informativo (sin persistencia)

```mermaid
sequenceDiagram
  autonumber
  actor U as Usuario (Inversionista)
  participant FE as Frontend (React)
  participant JWT as JwtAuthFilter
  participant OC as OrderController
  participant TS as TradingService
  participant MDA as MarketDataAdapter
  participant ALP as Alpaca Data (ext)
  participant CM as CommissionManager
  participant PG as PostgreSQL

  U->>FE: Selecciona AAPL × 10, ve OrderForm
  FE->>+OC: POST /api/v1/orders/quote<br/>{ticker, side:BUY, quantity:10}
  OC->>JWT: Bearer JWT
  JWT-->>OC: AuthenticatedUser(userId)
  OC->>+TS: quote(userId, request)
  TS->>TS: Valida ticker ∈ 25, quantity ≤ MAX
  TS->>+MDA: getLatestPrice(AAPL)
  MDA->>+ALP: GET /v2/stocks/AAPL/snapshot
  ALP-->>-MDA: { price: 184.50 }
  MDA-->>-TS: BigDecimal 184.50
  TS->>+CM: calculate(INVESTOR, 184.50×10)
  CM-->>-TS: commission 36.90 (HALF_UP, 2dec)
  TS->>+PG: SELECT balance FROM app.user_balances WHERE user_id=?
  PG-->>-TS: 10000.00
  TS->>TS: totalCost = 1845 + 36.90 = 1881.90<br/>sufficientFunds = true
  TS-->>-OC: QuoteResponse
  OC-->>-FE: 200 OK { estimatedTotal:1881.90, sufficientFunds:true, ... }
  FE->>U: Muestra "Total: USD 1881.90"<br/>Botón "Confirmar compra" habilitado
```

> El quote **no** persiste, **no** llama a Alpaca Trading, **no** toma locks. Solo lee precio + balance y calcula. Es seguro repetirlo N veces sin efectos.

---

## Fase 2 — Confirmación, ejecución y cadena post-commit

```mermaid
sequenceDiagram
  autonumber
  actor U as Usuario
  participant FE as Frontend
  participant OC as OrderController
  participant TS as TradingService
  participant IL as IdempotencyLock<br/>(ConcurrentHashMap)
  participant PG as PostgreSQL
  participant MDA as MarketDataAdapter
  participant AA as AlpacaAdapter<br/>(+ Retry r4j)
  participant ALP as Alpaca Trading (ext)
  participant OO as OrderOrchestrator
  participant TX as @TxnEventListener<br/>AFTER_COMMIT
  participant N as Notifier (Mail)
  participant A as AuditLogger
  participant ES as ElasticSearch

  U->>FE: Presiona "Confirmar compra"
  FE->>FE: clientOrderId = crypto.randomUUID()
  FE->>+OC: POST /api/v1/orders<br/>{clientOrderId, ticker, side:BUY, qty:10}
  OC->>+TS: placeOrder(userId, request)
  TS->>IL: tryAcquire(clientOrderId)
  IL-->>TS: lock OK

  rect rgb(240, 248, 255)
    note right of TS: Transacción JPA (REQUIRES_NEW)
    TS->>PG: SELECT * FROM app.orders WHERE client_order_id=? (idempotencia)
    PG-->>TS: empty
    TS->>PG: SELECT balance FROM app.user_balances<br/>WHERE user_id=? FOR UPDATE
    PG-->>TS: 10000.00 (lock tomado)
    TS->>MDA: getLatestPrice(AAPL) (re-fetch)
    MDA-->>TS: 184.50
    TS->>TS: re-valida fondos: 10000 ≥ 1881.90 ✓
    TS->>PG: INSERT INTO app.orders<br/>(status=PENDING, quoted_total=1881.90, ...)
    PG-->>TS: order_id (UUID)
    TS->>+AA: submitMarketOrder(symbol, qty, side, clientOrderId)
    AA->>+ALP: POST /v2/orders (+ Retry: 1s/3s/5s)
    ALP-->>-AA: { id:alp_xxx, status:filled, filled_avg_price:184.62 }
    AA-->>-TS: AlpacaResponse
    TS->>+OO: handleAlpacaResponse(order, response)
    OO->>PG: UPDATE app.orders<br/>SET status=EXECUTED, execution_unit_price=184.62, ...
    OO->>PG: UPDATE app.user_balances<br/>SET balance = balance − 1883.10
    OO->>PG: UPSERT app.positions (AAPL, qty+=10,<br/>avg_buy_price recalc)
    OO-->>-TS: ok
    TS->>PG: COMMIT
  end

  TS-->>-OC: PlaceOrderResult(isNew=true, OrderResponse)
  OC-->>-FE: 201 Created { status:EXECUTED, executionUnitPrice:184.62, ... }
  FE->>U: Redirige a /portfolio con toast emerald

  rect rgb(245, 245, 220)
    note over TX,ES: Post-commit (fuera de la transacción)
    TX->>+N: notifyOrderExecuted(userId, order)
    N->>N: Render Thymeleaf template
    N-->>-TX: SMTP a MailHog
    TX->>+A: emit(ORDER_CREATED) + emit(ORDER_EXECUTED)
    A->>+ES: POST /audit/_doc<br/>{event, actor, resource, result, ip, ...}
    ES-->>-A: 201
    A-->>-TX: ok
  end
```

---

## Notas sobre el modelo

- **Idempotencia.** El `clientOrderId` lo genera el frontend (`crypto.randomUUID()`) y el backend lo persiste como columna única en `app.orders`. Doble-click → la segunda llamada encuentra la fila ya creada y responde `200 OK` con la misma orden — **no llama a Alpaca otra vez**, no descuenta saldo. (SPEC §5.2.3.)
- **Lock pessimistic.** El `SELECT ... FOR UPDATE` sobre `user_balances` evita race conditions con compras simultáneas del mismo usuario.
- **Resilience4j.** El `AlpacaAdapter` envuelve el call HTTP con `@Retry(name="alpacaApi")` — 3 intentos a 1s/3s/5s. Si los 3 fallan, sale `502 ALPACA_UNAVAILABLE` y la transacción hace rollback (TAC-D2, `ARCHITECTURE.md` §6.3).
- **Cadena post-commit.** `OrderOrchestrator` confirma la transacción y luego un `@TransactionalEventListener(AFTER_COMMIT)` dispara notificación + auditoría. Si una de las dos falla, **la orden ya está commitada** — la deuda de reconciliación queda como evento manual (memoria del usuario: D27 F09 + D18 F10 — `noRollbackFor` aplicado a métodos `@Transactional` anidados).
- **ESC-I1.** El SLO "confirmación + portafolio actualizado + notificación en <5s" se cumple porque la transacción JPA principal corre síncrona, típicamente <500ms total (SPEC §13).

## Flujos no representados aquí

- **`accepted` no terminal** (D29 F09 — orden encolada en Alpaca sin filled inmediato): se persiste con `status=PENDING`, sin débito de saldo, sin upsert de posición. Reconciliación pendiente (deuda viva).
- **Idempotencia duplicada en concurrencia real**: el `IdempotencyLock` en memoria (`ConcurrentHashMap`) evita dos hits paralelos del mismo `clientOrderId`. Sirve para una sola instancia del backend; con N instancias, habría que mover el lock a Redis (deuda post-MVP).
- **Errores 4xx/5xx**: ver SPEC §5.3 — saldo insuficiente (409), ticker inválido (400), Alpaca rechaza (422), retries agotados (502).
