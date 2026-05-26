# Diagrama de Secuencia — Orden de Venta Market (HU-F10)

**Fuente:** `specs/HU-F10-orden-venta-market/SPEC.md` §5.1.
**Última actualización:** 2026-05-25.

Variación de [la orden de compra (F09)](sequence-orden-compra-market.md). El esqueleto del flujo es idéntico (mismo `OrderController`, misma transacción JPA, mismo `OrderOrchestrator`, misma cadena post-commit). Las diferencias clave están en:

1. La **validación de posición** reemplaza la validación de fondos como bloqueante.
2. El balance se **acredita** (no se descuenta); la posición se **decrementa** (no se incrementa).
3. La semántica de `estimatedTotal` cambia: en BUY es `subtotal + commission` (descuento), en SELL es `subtotal − commission` (producto neto).

Este documento muestra solo el flujo `SELL`; para todo lo común consultar F09.

---

## Fase 1 — Quote SELL informativo

```mermaid
sequenceDiagram
  autonumber
  actor U as Usuario
  participant FE as Frontend
  participant OC as OrderController
  participant TS as TradingService
  participant MDA as MarketDataAdapter
  participant ALP as Alpaca Data (ext)
  participant CM as CommissionManager
  participant PG as PostgreSQL

  U->>FE: Toggle SELL, ticker=AAPL, qty=5
  FE->>+OC: POST /api/v1/orders/quote<br/>{ticker, side:SELL, qty:5}
  OC->>+TS: quote(userId, request)
  TS->>TS: Valida ticker ∈ 25, qty ≤ MAX
  TS->>+PG: SELECT quantity FROM app.positions<br/>WHERE user_id=? AND ticker=?
  PG-->>-TS: 10 (posición existente)
  TS->>TS: sufficientShares = (10 ≥ 5) = true<br/>userShares = 10
  TS->>+MDA: getLatestPrice(AAPL)
  MDA->>+ALP: snapshot
  ALP-->>-MDA: 190.00
  MDA-->>-TS: 190.00
  TS->>+CM: calculate(INVESTOR, 950)
  CM-->>-TS: 19.00
  TS->>TS: estimatedProceeds = 950 − 19 = 931.00<br/>(semántica SELL: subtotal − commission)
  TS-->>-OC: QuoteResponse
  OC-->>-FE: 200 OK<br/>{sufficientShares:true, userShares:10,<br/>estimatedTotal:931.00, ...}
  FE->>U: "Producto neto: USD 931.00 — tienes 10 AAPL, te quedarán 5"
```

> Si el usuario no tiene posición o tiene menos cantidad de la requerida, el quote responde 200 con `sufficientShares=false` (NO 4xx — el quote es informativo, igual que `sufficientFunds=false` en F09 §5.2.1). El frontend deshabilita "Confirmar venta".

---

## Fase 2 — Confirmación + ejecución + cadena post-commit

```mermaid
sequenceDiagram
  autonumber
  actor U as Usuario
  participant FE as Frontend
  participant OC as OrderController
  participant TS as TradingService
  participant IL as IdempotencyLock
  participant PG as PostgreSQL
  participant MDA as MarketDataAdapter
  participant AA as AlpacaAdapter<br/>(+ Retry)
  participant ALP as Alpaca Trading (ext)
  participant OO as OrderOrchestrator
  participant TX as @TxnEventListener<br/>AFTER_COMMIT
  participant N as Notifier
  participant A as AuditLogger

  U->>FE: "Confirmar venta"
  FE->>FE: clientOrderId = randomUUID()
  FE->>+OC: POST /api/v1/orders<br/>{clientOrderId, ticker, side:SELL, qty:5}
  OC->>+TS: placeOrder(userId, request)
  TS->>IL: tryAcquire(clientOrderId)

  rect rgb(255, 245, 238)
    note right of TS: Transacción JPA (REQUIRES_NEW)
    TS->>PG: SELECT * FROM app.orders WHERE client_order_id=?
    PG-->>TS: empty
    TS->>PG: SELECT * FROM app.positions<br/>WHERE user_id=? AND ticker=? FOR UPDATE
    PG-->>TS: quantity=10, avg_buy_price=184.62
    TS->>TS: Valida sufficientShares: 10 ≥ 5 ✓<br/>(si NO → ShortSelling / InsufficientShares → 409 + rollback)
    TS->>MDA: re-fetch precio
    MDA-->>TS: 189.95
    TS->>TS: re-calcula commission + proceeds
    TS->>PG: INSERT INTO app.orders<br/>(side=SELL, status=PENDING, quoted_total=931.00)
    PG-->>TS: order_id
    TS->>+AA: submitMarketOrder(side=sell, qty=5, clientOrderId)
    AA->>+ALP: POST /v2/orders (Retry r4j 1s/3s/5s)
    ALP-->>-AA: {status:filled, filled_avg_price:189.95}
    AA-->>-TS: AlpacaResponse
    TS->>+OO: handleAlpacaResponse(order, response)
    OO->>PG: UPDATE app.orders SET status=EXECUTED,<br/>execution_unit_price=189.95,<br/>execution_total=930.75
    OO->>PG: UPDATE app.positions<br/>SET quantity = quantity − 5<br/>WHERE user_id=? AND ticker=?
    note right of PG: Si quantity resultante = 0 →<br/>DELETE FROM app.positions<br/>avg_buy_price NO se modifica
    OO->>PG: UPDATE app.user_balances<br/>SET balance = balance + 930.75
    OO-->>-TS: ok
    TS->>PG: COMMIT
  end

  TS-->>-OC: PlaceOrderResult
  OC-->>-FE: 201 Created<br/>{side:SELL, status:EXECUTED, executionTotal:930.75}
  FE->>U: Toast emerald "✅ Vendiste 5 AAPL a 189.95 — Recibiste 930.75"

  rect rgb(245, 245, 220)
    note over TX,A: Post-commit
    TX->>N: notifyOrderExecutedSell(userId, order)
    N->>N: SMTP MailHog ("Vendiste 5 AAPL...")
    TX->>A: emit(ORDER_CREATED), emit(ORDER_EXECUTED)<br/>details: {side:SELL, positionResultingQty:5}
  end
```

---

## Diferencias contra F09 (BUY) — tabla resumen

| Aspecto | BUY (F09) | SELL (F10) |
|---|---|---|
| Validación bloqueante | `balance ≥ totalCost` (fondos) | `position.quantity ≥ sellQuantity` (acciones) |
| Lock pessimistic | `user_balances FOR UPDATE` | `positions FOR UPDATE` (+ balance al actualizar) |
| Excepción específica de SELL | — | `ShortSellingNotAllowedException`, `InsufficientSharesException` (ambas → 409) |
| Efecto sobre balance | `balance −= execution_total` | `balance += execution_total` |
| Efecto sobre posición | UPSERT (`qty +=`, recalcula `avg_buy_price`) | `qty −=`. Si `qty=0` → DELETE. `avg_buy_price` **NO** se modifica. |
| Semántica `estimatedTotal` | `subtotal + commission` (descuento) | `subtotal − commission` (producto neto) |
| Email post-commit | "Tu orden de compra de 10 AAPL se ejecutó a USD 184.62" | "Vendiste 5 AAPL a USD 189.95. Producto neto: USD 930.75" |

## Decisiones registradas (extracto de SPEC §5.1)

- **`avg_buy_price` no se modifica en venta.** Mientras quede tenencia, refleja el precio promedio histórico de compra — base para que HU-F16 calcule ganancia/pérdida.
- **`accepted` no terminal en SELL (D-SELL-QUEUED-RISK heredado D29 F09).** Si Alpaca encola la orden, la posición se decrementa optimistamente pero el balance NO se acredita aún. Si Alpaca luego cancela, el usuario perdió posición sin recibir crédito. MVP no mitiga; deuda registrada.
- **Memoria del usuario — `noRollbackFor` en nested @Transactional (D27 F09 + D18 F10):** la `ShortSellingNotAllowedException` se marca con `noRollbackFor` en el método interno para que el handler global responda 409 en vez de 500 `UnexpectedRollbackException`. Aplicar siempre que un `@Transactional` anidado lance excepción de dominio.

## Flujos no representados aquí

- Idempotencia por `clientOrderId` (igual que F09; ver SPEC F09 §5.2.3).
- Errores 4xx/5xx (SPEC §5.3): `SHORT_SELLING_NOT_ALLOWED` (409), `INSUFFICIENT_SHARES` (409), Alpaca rejected (422), retries agotados (502).
- Path `accepted` no terminal — ver decisión D-SELL-QUEUED-RISK arriba.
