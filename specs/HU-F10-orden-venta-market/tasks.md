# tasks.md — HU-F10 Orden de venta Market

> Descomposición granular del `plan.md` (SDD Paso 3).
> Cadencia: lotes lógicos con validación en HITOs [[feedback-cadencia-sdd]].
> Rama: `feat/HU-F10-orden-venta-market`.
> Commits con `refs HU-F10 specs/HU-F10-orden-venta-market/SPEC.md` + `Co-authored-by: Claude <noreply@anthropic.com>`.

Leyenda: ☐ pendiente · ◐ en progreso · ☑ hecho · ✗ cancelado/diferido

---

## Lote A — PortfolioService.credit + decrementPosition + excepciones + verificación pre-coding

> Objetivo: extender `PortfolioService` con las 2 operaciones inversas que necesita SELL (credit a balance, decrement a posición con DELETE en qty=0). Crear las 2 excepciones nuevas. Verificar que V5 (de F09) ya soporta F10 (D13). SIN tocar TradingService aún.

### Verificación pre-coding (D13)

- ☐ **T1.1** `docker compose up -d postgres` (si no está corriendo).
- ☐ **T1.2** `docker compose exec postgres psql -U bloomtrade -d bloomtrade -c "\d app.orders"` — confirmar que `chk_order_side` incluye `CHECK (side IN ('BUY', 'SELL'))`. Si NO → STOP y crear `V6__extend_order_side_check.sql` con `ALTER TABLE app.orders DROP CONSTRAINT chk_order_side; ALTER TABLE app.orders ADD CONSTRAINT chk_order_side CHECK (side IN ('BUY', 'SELL'));` antes de continuar.
- ☐ **T1.3** `docker compose exec postgres psql -U bloomtrade -d bloomtrade -c "\d app.positions"` — confirmar `chk_position_quantity CHECK (quantity >= 0)`. Si NO → STOP.
- ☐ **T1.4** `docker compose exec postgres psql -U bloomtrade -d bloomtrade -c "SELECT side, COUNT(*) FROM app.orders GROUP BY side;"` — verificar al menos una orden `BUY` del demo F09 existe (sirve para tener `app.positions` con tenencia real para HITO 5 después).

### Repository extensions

- ☐ **T1.5** `portfolio/repository/PositionRepository.java` MODIFICADO — agregar 2 métodos:
    ```java
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Position p WHERE p.userId = :userId AND p.ticker = :ticker")
    Optional<Position> findByUserIdAndTickerForUpdate(@Param("userId") UUID userId, @Param("ticker") String ticker);

    @Modifying
    @Query("DELETE FROM Position p WHERE p.userId = :userId AND p.ticker = :ticker")
    int deleteByUserIdAndTicker(@Param("userId") UUID userId, @Param("ticker") String ticker);
    ```
    El `int` de `deleteByUserIdAndTicker` permite handle de 0 rows si la fila ya fue borrada por una transacción concurrente.

### Domain extension

- ☐ **T1.6** `portfolio/domain/Position.java` MODIFICADO — agregar método de dominio:
    ```java
    public void decrementBy(int sellQty) {
        if (sellQty <= 0) {
            throw new IllegalArgumentException("sellQty must be positive");
        }
        if (sellQty > this.quantity) {
            throw new IllegalStateException("sellQty exceeds available quantity");
        }
        this.quantity -= sellQty;
        this.updatedAt = Instant.now();
        // avg_buy_price NO se modifica en venta — sigue reflejando el promedio histórico de compras.
    }

    public boolean isDepleted() {
        return this.quantity == 0;
    }
    ```

### Service extensions (D5, D8, D14)

- ☐ **T1.7** `portfolio/service/PortfolioService.java` MODIFICADO — agregar 2 métodos públicos:
    ```java
    @Transactional
    public BigDecimal credit(UUID userId, BigDecimal amount) {
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Credit amount must be positive");
        }
        UserBalance balance = userBalanceRepository.findByUserIdForUpdate(userId)
            .orElseThrow(() -> new IllegalStateException("Balance not found for userId=" + userId));
        BigDecimal newBalance = balance.getBalance().add(amount);
        balance.setBalance(newBalance);
        // D14: NO noRollbackFor — si la SELL falla, todo se revierte limpiamente.
        return newBalance;
    }

    /**
     * Decrementa la posición del usuario para el ticker.
     * - Si la cantidad resultante es 0, DELETE la fila (D1).
     * - Lock pessimistic D12: este método se llama DESPUÉS de findByUserIdForUpdate sobre user_balances
     *   para mantener orden consistente y evitar deadlocks BUY↔SELL.
     *
     * @return Optional.empty() si la fila fue borrada; Optional.of(updated) si quedó tenencia.
     */
    @Transactional
    public Optional<Position> decrementPosition(UUID userId, String ticker, int sellQuantity) {
        Position position = positionRepository.findByUserIdAndTickerForUpdate(userId, ticker)
            .orElseThrow(() -> new ShortSellingNotAllowedException(ticker, sellQuantity));
        if (position.getQuantity() < sellQuantity) {
            throw new InsufficientSharesException(position.getQuantity(), sellQuantity, ticker);
        }
        position.decrementBy(sellQuantity);
        if (position.isDepleted()) {
            positionRepository.deleteByUserIdAndTicker(userId, ticker);
            return Optional.empty();
        }
        return Optional.of(position);
    }

    @Transactional(readOnly = true)
    public Optional<Position> findPosition(UUID userId, String ticker) {
        return positionRepository.findByUserIdAndTicker(userId, ticker);
    }
    ```

### Excepciones nuevas

- ☐ **T1.8** `trading/exception/ShortSellingNotAllowedException.java` — `RuntimeException` con campos `ticker, requestedQty`. Mensaje: `"No tienes posición en " + ticker + ". BloomTrade no permite ventas en corto."`.
- ☐ **T1.9** `trading/exception/InsufficientSharesException.java` — `RuntimeException` con campos `available, requested, ticker`. Mensaje: `"Solo tienes " + available + " " + ticker + " disponibles para vender (solicitaste " + requested + ")"`.

### Tests del Lote A

- ☐ **T1.10** `PortfolioServiceCreditTest.java` (o extender el existente `PortfolioServiceTest`):
    - `credit_happyPath_increasesBalance`: balance 1000, credit 500 → newBalance 1500.
    - `credit_preservesBigDecimalPrecision`: balance 1000.50, credit 930.75 → newBalance 1931.25 (assert scale=2).
    - `credit_negativeAmount_throwsIllegalArgument`: credit -100 → IllegalArgumentException.
    - `credit_zeroAmount_throwsIllegalArgument`: credit 0 → IllegalArgumentException.
    - `credit_balanceNotFound_throwsIllegalState`: userId sin fila balance → IllegalStateException.
- ☐ **T1.11** `PortfolioServiceDecrementPositionTest.java`:
    - `decrement_happyPath_residualQty`: posición {qty=10, avg=184.62}, decrement 3 → Optional.of(Position{qty=7, avg=184.62}). Verifica que `avg_buy_price` NO cambia.
    - `decrement_exactQty_deletesRow`: posición {qty=5, avg=184.62}, decrement 5 → Optional.empty() + `positionRepository.findByUserIdAndTicker` retorna Optional.empty() post-flush.
    - `decrement_insufficientShares_throwsAndNoPersist`: posición {qty=3}, decrement 5 → InsufficientSharesException(available=3, requested=5). Verifica que la fila sigue qty=3.
    - `decrement_shortSelling_throwsAndNoPersist`: sin posición, decrement 5 → ShortSellingNotAllowedException(ticker, 5). Verifica que NO se crea fila.
    - `decrement_existingPositionZeroQty_throwsShortSelling`: posición existente con qty=0 (caso edge, no debería pasar por DELETE pero defensivo) → ShortSellingNotAllowedException (porque `qty < requested` lanza InsufficientShares; pero qty=0 con `requested>0` también es shortSelling semánticamente — decisión: si qty=0 lanzar SHORT_SELLING). Implementación: cambiar el orden de checks en decrementPosition o tratar qty=0 como "fila no existe efectivamente".
- ☐ **T1.12** `PositionRepositoryConcurrencyTest.java` — opcional pero recomendado: dos threads llaman `findByUserIdAndTickerForUpdate` simultáneamente sobre mismo `(userId, ticker)`; el segundo debe esperar al commit del primero. Si flakea: marcar `@Disabled` con TODO; la garantía formal viene de Lote D concurrencia E2E.

### Verificación HITO 1

- ☐ **T1.13** `mvn -f backend/pom.xml -Dtest='PortfolioServiceCreditTest,PortfolioServiceDecrementPositionTest' test` verde.
- ☐ **T1.14** `mvn -f backend/pom.xml compile` verde (compila el rest del proyecto sin romper).
- ☐ **T1.15** **← HITO 1 ✅** PortfolioService extendido + 2 excepciones nuevas + tests verdes + V5 verificada apta para F10.

---

## Lote B — TradingService dispatch SELL + OrderOrchestrator + handlers + ValidationMessages

> Objetivo: habilitar `side=SELL` en `TradingService.quote` y `placeOrder` con dispatch interno (D5). Extender `OrderOrchestrator` para la secuencia post-Alpaca de SELL. Mapear las 2 excepciones nuevas a 409. Lock order D12 documentado.

### TradingService extensions (D5, D12, D15)

- ☐ **T2.1** `trading/service/TradingService.java` MODIFICADO — `quote(userId, request)`:
    - Eliminar la rama `if (side == SELL) throw new InvalidSideException("SIDE_NOT_YET_IMPLEMENTED")`.
    - Agregar lógica side-aware:
        ```java
        boolean sufficientShares = true;
        int userShares = 0;
        BigDecimal estimatedTotal;

        if (request.side() == OrderSide.SELL) {
            // D15: NO tomar lock pessimistic durante quote (informativo, no compromete recursos).
            Optional<Position> positionOpt = portfolioService.findPosition(userId, request.ticker());
            userShares = positionOpt.map(Position::getQuantity).orElse(0);
            sufficientShares = userShares >= request.quantity();
            estimatedTotal = subtotal.subtract(commission);  // producto neto
        } else {
            estimatedTotal = subtotal.add(commission);  // lo que se descuenta (legacy BUY)
        }
        // sufficientFunds para BUY se mantiene; para SELL siempre true (vender NUNCA descuenta de balance).
        boolean sufficientFunds = (request.side() == OrderSide.BUY)
            ? balance.compareTo(estimatedTotal) >= 0
            : true;
        ```
    - Construir `QuoteResponse` con los nuevos campos `sufficientShares` y `userShares` (ver T2.6).
- ☐ **T2.2** `trading/service/TradingService.java` MODIFICADO — `placeOrderTx(userId, request)` con dispatch interno por side:
    ```java
    @Transactional(noRollbackFor = {AlpacaApiException.class, AlpacaOrderRejectedException.class,
                                     InsufficientFundsException.class, ShortSellingNotAllowedException.class,
                                     InsufficientSharesException.class})
    public OrderResponse placeOrderTx(UUID userId, PlaceOrderRequest request) {
        // ... idempotency check, account check, ticker/quantity validation comunes ...

        if (request.side() == OrderSide.BUY) {
            return handleBuyTx(userId, request, unitPrice, commission, quotedTotal);
        } else {
            return handleSellTx(userId, request, unitPrice, commission, quotedTotal);
        }
    }
    ```
    Razones del `noRollbackFor` extendido: análogo a F09 D24 — preservar las filas FAILED/REJECTED cuando la excepción propaga.
- ☐ **T2.3** `trading/service/TradingService.java` — agregar método privado `handleSellTx(...)`:
    1. **Lock order D12**: NO tomar lock sobre user_balances acá (no se necesita pre-validation para SELL — vender no descuenta de balance). Solo tomar lock sobre positions.
    2. `Optional<Position> position = portfolioService.decrementPosition(userId, request.ticker(), request.quantity())` — esto ya valida SHORT_SELLING_NOT_ALLOWED + INSUFFICIENT_SHARES atómicamente con lock pessimistic. **PERO**: necesitamos decrementar SOLO después de Alpaca filea, no antes. **Decisión emergente — DXX**: dividir en 2 fases:
        - Fase pre-Alpaca: validar (lock + check) pero NO mutar. Método nuevo `PortfolioService.validateSellable(userId, ticker, sellQty)` que ejecuta el SELECT FOR UPDATE + lanza excepciones SIN decrementar.
        - Fase post-Alpaca: decrementar (`decrementPosition`) en la transacción que ya tiene el lock heredado.
        > **Trade-off:** mantener el lock entre validate y mutate exige que ambas operaciones estén en la misma `@Transactional`. Spring JPA lo garantiza si ambas se invocan desde `placeOrderTx`.
    3. INSERT order PENDING (igual que F09 BUY).
    4. Llamar Alpaca con `side="sell"`.
    5. Si Alpaca `filled`:
        - `Order.markAsExecuted(alpacaOrderId, executionUnitPrice)`.
        - `executionTotal = executionUnitPrice × quantity - commission` (producto neto).
        - Setear `order.executionTotal = executionTotal` (override del calc default de markAsExecuted que asume BUY).
        - `Optional<Position> updated = portfolioService.decrementPosition(userId, ticker, sellQty)` — efectiva la mutación.
        - `BigDecimal newBalance = portfolioService.credit(userId, executionTotal)`.
        - Publicar `OrderExecutedEvent` con `side=SELL`, `positionDeleted = updated.isEmpty()`, `positionResultingQty = updated.map(Position::getQuantity).orElse(0)`.
    6. Si Alpaca `accepted` (D9 D-SELL-QUEUED-RISK): mismo path que F09 D29 BUY adaptado:
        - `Order.linkToAlpaca(alpacaOrderId)` sin transicionar status (queda PENDING).
        - **Decrementar posición optimistamente** vía `decrementPosition` (D9 trade-off).
        - **NO acreditar balance** (no sabemos `executionUnitPrice` real).
        - Publicar `OrderQueuedEvent` con `side=SELL`.
    7. Si Alpaca `rejected` (`AlpacaOrderRejectedException`):
        - `Order.markAsRejected("ALPACA_ORDER_REJECTED", reason)`.
        - **NO decrementar posición ni acreditar balance.**
        - Publicar `OrderRejectedEvent` con `side=SELL`.
    8. Si Alpaca down (`AlpacaApiException`):
        - `Order.markAsFailed("ALPACA_API_ERROR", message)`.
        - **NO decrementar ni acreditar.**
        - Publicar `OrderFailedEvent` con `side=SELL`.
- ☐ **T2.4** `portfolio/service/PortfolioService.java` — agregar método `validateSellable(userId, ticker, sellQty)` que ejecuta el lock + checks SIN decrementar:
    ```java
    @Transactional
    public Position validateSellable(UUID userId, String ticker, int sellQty) {
        Position position = positionRepository.findByUserIdAndTickerForUpdate(userId, ticker)
            .orElseThrow(() -> new ShortSellingNotAllowedException(ticker, sellQty));
        if (position.getQuantity() < sellQty) {
            throw new InsufficientSharesException(position.getQuantity(), sellQty, ticker);
        }
        return position;  // Lock retenido durante la transacción del caller.
    }
    ```
    > **Decisión emergente DXX (que se documentará en plan.md sección 2.3 post-cierre)**: separar validateSellable de decrementPosition para permitir el patrón "valida antes de Alpaca, decrementa después". Justificación: si decrementáramos antes de llamar Alpaca, un Alpaca-rejected requeriría re-incrementar (más código, más bugs potenciales). Mantener el lock entre las 2 ops es seguro porque ambas viven en la misma `@Transactional`.

### Order entity extension

- ☐ **T2.5** `trading/domain/Order.java` MODIFICADO — ajustar `markAsExecuted(alpacaOrderId, executionUnitPrice)` para ser side-aware:
    ```java
    public void markAsExecuted(String alpacaOrderId, BigDecimal executionUnitPrice) {
        this.alpacaOrderId = alpacaOrderId;
        this.executionUnitPrice = executionUnitPrice;
        BigDecimal subtotal = executionUnitPrice.multiply(BigDecimal.valueOf(this.quantity));
        // Side-aware: BUY suma commission al total cobrado; SELL resta commission del producto neto.
        if (this.side == OrderSide.BUY) {
            this.executionTotal = subtotal.add(this.quotedCommission).setScale(4, RoundingMode.HALF_UP);
        } else {
            this.executionTotal = subtotal.subtract(this.quotedCommission).setScale(4, RoundingMode.HALF_UP);
        }
        this.status = OrderStatus.EXECUTED;
        this.executedAt = Instant.now();
    }
    ```
    Esto evita que el caller tenga que hacer el branching. Test parametrizado verifica ambos sides.

### DTO extension (D5)

- ☐ **T2.6** `trading/dto/QuoteResponse.java` MODIFICADO — agregar 2 campos:
    ```java
    public record QuoteResponse(
        String ticker, OrderSide side, int quantity,
        String estimatedUnitPrice, String estimatedSubtotal,
        String commission,
        @Schema(description = "Side-aware: BUY = subtotal + commission (lo descontado); SELL = subtotal - commission (producto neto acreditado).")
        String estimatedTotal,
        String currency, String userBalance,
        boolean sufficientFunds,
        @Schema(description = "Solo significativo para SELL. Para BUY siempre true.")
        boolean sufficientShares,
        @Schema(description = "Cantidad actual del usuario para el ticker. Para BUY = informativo (0 si no tiene posición). Para SELL = validar contra quantity.")
        int userShares,
        boolean marketOpen,
        Instant quotedAt
    ) {}
    ```
- ☐ **T2.7** `trading/dto/OrderResponse.java` MODIFICADO — agregar @Schema description side-aware a `quotedTotal` y `executionTotal` (sin cambio estructural):
    ```java
    @Schema(description = "Side-aware: BUY = total cobrado real; SELL = producto neto acreditado real.")
    String executionTotal,
    ```

### Handlers + i18n

- ☐ **T2.8** `shared/web/GlobalExceptionHandler.java` MODIFICADO — agregar 2 handlers:
    ```java
    @ExceptionHandler(ShortSellingNotAllowedException.class)
    public ResponseEntity<ErrorResponse> handleShortSelling(ShortSellingNotAllowedException ex) {
        ErrorResponse body = ErrorResponse.builder()
            .error("SHORT_SELLING_NOT_ALLOWED")
            .message(validationMessages.get("SHORT_SELLING_NOT_ALLOWED", ex.getTicker()))
            .details(Map.of("ticker", ex.getTicker(), "requestedQty", ex.getRequestedQty()))
            .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(InsufficientSharesException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientShares(InsufficientSharesException ex) {
        ErrorResponse body = ErrorResponse.builder()
            .error("INSUFFICIENT_SHARES")
            .message(validationMessages.get("INSUFFICIENT_SHARES", ex.getAvailable(), ex.getTicker(), ex.getRequested()))
            .details(Map.of("available", ex.getAvailable(), "requested", ex.getRequested(), "ticker", ex.getTicker()))
            .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }
    ```
- ☐ **T2.9** `validation-messages.properties` MODIFICADO — agregar 2 códigos:
    ```
    SHORT_SELLING_NOT_ALLOWED=No tienes posición en {0}. BloomTrade no permite ventas en corto.
    INSUFFICIENT_SHARES=Solo tienes {0} {1} disponibles para vender (solicitaste {2}).
    ```
- ☐ **T2.10** `trading/dto/QuoteRequest.java` y `PlaceOrderRequest.java` — verificar que el `@JsonValue`/`@JsonCreator` del enum `OrderSide` acepta tanto "BUY" como "SELL" (debería ya funcionar; sólo confirmar).

### Audit event details (D11)

- ☐ **T2.11** `trading/event/OrderExecutedEvent.java` MODIFICADO — agregar 2 campos:
    ```java
    public record OrderExecutedEvent(
        UUID orderId, UUID userId, String ticker, OrderSide side,
        int quantity, BigDecimal executionUnitPrice, BigDecimal executionTotal,
        BigDecimal commission, BigDecimal newBalance,
        Integer positionResultingQty,  // NUEVO: cantidad post-mutación (0 si BUY y nueva fila; N si BUY/SELL con tenencia)
        Boolean positionDeleted        // NUEVO: solo true en SELL que liquidó posición completa
    ) {}
    ```
    BUY popula `positionResultingQty` con el qty post-upsert, `positionDeleted=false`. SELL popula ambos según resultado de `decrementPosition`.
- ☐ **T2.12** `trading/event/OrderEventListener.java` MODIFICADO — `onOrderExecuted` agrega los 2 campos al `Auditor.emit(ORDER_EXECUTED, details)`. NO cambia event types (sigue el mismo `ORDER_EXECUTED` enum).

### Tests del Lote B

- ☐ **T2.13** `TradingServiceQuoteSellTest.java` (o extender `TradingServiceTest`):
    - `quote_sellHappyPath_returnsNetProceeds`: usuario con posición {qty=10}, request SELL × 5, price=190 → estimatedTotal="931.00" (no 969.00), sufficientShares=true, userShares=10.
    - `quote_sellWithoutPosition_returnsSufficientSharesFalse`: sin posición, request SELL × 1 → sufficientShares=false, userShares=0, estimatedTotal poblado (informativo).
    - `quote_sellWithInsufficientPosition_returnsSufficientSharesFalse`: posición {qty=3}, request SELL × 5 → sufficientShares=false, userShares=3.
- ☐ **T2.14** `TradingServicePlaceOrderSellTest.java`:
    - `placeOrder_sellHappyPath_executed_decrementsPositionAndCreditsBalance`: mock Alpaca filled, posición {qty=10}, SELL × 5 → orden EXECUTED, position decrementado a 5, balance creditado por `executionTotal`. Verifica que se publica `OrderExecutedEvent` con `side=SELL`, `positionResultingQty=5`, `positionDeleted=false`.
    - `placeOrder_sellExactQty_deletesPositionRow`: posición {qty=5}, SELL × 5 → orden EXECUTED, position fila borrada, evento con `positionDeleted=true`.
    - `placeOrder_sellShortSelling_throws409_noAlpacaCall`: sin posición, SELL × 1 → ShortSellingNotAllowedException. Verifica que `alpacaTradingAdapter.submitMarketOrder` NUNCA fue invocado (Mockito.verify(adapter, never())...).
    - `placeOrder_sellInsufficientShares_throws409_noAlpacaCall`: posición {qty=3}, SELL × 5 → InsufficientSharesException. Mismo verify never sobre adapter.
    - `placeOrder_sellAlpacaRejected_marksRejected_positionUntouched`: posición {qty=10}, mock Alpaca rejected → orden REJECTED, position sigue qty=10, balance sin cambios.
    - `placeOrder_sellAlpacaDown_marksFailed_positionUntouched`: mock Alpaca down × 3 retries → orden FAILED, position sin cambios.
    - `placeOrder_sellIdempotency_returnsExistingOrder`: ejecutar placeOrder SELL OK 2 veces con mismo clientOrderId → segunda retorna mismo id, NO decrementa segunda vez, NO acredita segunda vez. Verifica `alpacaTradingAdapter` invocado 1 vez total.
- ☐ **T2.15** `OrderMarkAsExecutedSideAwareTest.java` (test del entity Order):
    - `markAsExecuted_buy_addsCommissionToTotal`: side=BUY, qty=10, price=184.62, commission=36.90 → executionTotal=1883.10.
    - `markAsExecuted_sell_subtractsCommissionFromTotal`: side=SELL, qty=5, price=189.95, commission=19.00 → executionTotal=930.75.

### Verificación HITO 2

- ☐ **T2.16** `mvn -f backend/pom.xml -Dtest='TradingServiceQuoteSellTest,TradingServicePlaceOrderSellTest,OrderMarkAsExecutedSideAwareTest' test` verde.
- ☐ **T2.17** `mvn -f backend/pom.xml compile` verde (sin romper tests F09 existentes).
- ☐ **T2.18** `docker compose up -d --build backend` + abrir Swagger UI: confirmar que `QuoteResponse` muestra `sufficientShares` y `userShares` con descripción correcta; `OrderResponse` documenta semántica side-aware en `executionTotal`.
- ☐ **T2.19** **← HITO 2 ✅** TradingService maneja SELL completo + handlers nuevos + Swagger actualizado.

---

## Lote C — Notifier 3 métodos *Sell + rename templates F09 + 4 templates nuevas

> Objetivo: notificaciones email funcionan para SELL con wording correcto. Rename de templates F09 al sufijo `-buy` (D6). OrderEventListener despacha al método correcto por side (D7).

### Notifier extension (D7)

- ☐ **T3.1** `notification/Notifier.java` MODIFICADO — agregar 3 métodos:
    ```java
    void notifyOrderExecutedSell(UUID userId, OrderExecutedEvent event);
    void notifyOrderRejectedSell(UUID userId, OrderRejectedEvent event);
    void notifyOrderFailedSell(UUID userId, OrderFailedEvent event);
    ```
    Y renombrar los 3 métodos F09 existentes para simetría (D6):
    ```java
    // ANTES (F09):
    void notifyOrderExecuted(UUID userId, OrderExecutedEvent event);
    void notifyOrderRejected(UUID userId, OrderRejectedEvent event);
    void notifyOrderFailed(UUID userId, OrderFailedEvent event);

    // DESPUÉS (F10):
    void notifyOrderExecutedBuy(UUID userId, OrderExecutedEvent event);
    void notifyOrderRejectedBuy(UUID userId, OrderRejectedEvent event);
    void notifyOrderFailedBuy(UUID userId, OrderFailedEvent event);
    ```
    Si existe método para queued (D29 F09 H.5), análogamente: `notifyOrderQueuedBuy`/`notifyOrderQueuedSell`.
- ☐ **T3.2** `notification/MailNotifier.java` MODIFICADO — implementar los 3 métodos `*Sell` + actualizar los 3 (4) renombrados `*Buy` para apuntar a las nuevas rutas de templates. Cada método:
    1. Carga usuario para obtener `nombreCompleto` + `notificationChannel`.
    2. Si channel != EMAIL → log + skip.
    3. Renderiza template Thymeleaf con vars side-específicas (ver T3.5–T3.7).
    4. Envía vía JavaMailSender.
    5. Catch + audit `*_EMAIL_FAILED` (reutilizar event types F09; o agregar `ORDER_EXECUTED_SELL_EMAIL_FAILED` etc. — **decisión D-AUDIT-EMAIL-SIDE en plan.md emergente**: reutilizar event types sin sufijo side, agregar `side` al `details` del audit).

### Rename de templates F09 (D6)

- ☐ **T3.3** `git mv backend/src/main/resources/templates/email/order-executed.html backend/src/main/resources/templates/email/order-executed-buy.html` — preservar history.
- ☐ **T3.4** Mismo para `order-rejected.html`, `order-failed.html`, y `order-queued.html` si existe (D29 H.5 F09).

### Templates nuevas SELL

- ☐ **T3.5** `backend/src/main/resources/templates/email/order-executed-sell.html` NUEVO — Thymeleaf inline-CSS. Vars: `{nombreCompleto, ticker, quantity, executionUnitPrice, subtotal, commission, executionTotal (producto neto), newBalance, positionResultingQty, positionDeleted, executedAt}`. Mensaje:
    - Asunto: "Tu orden de venta de {quantity} {ticker} se ejecutó"
    - Cuerpo:
        - "Hola {nombreCompleto},"
        - "Tu orden de venta se ejecutó:"
        - Tabla: ticker, cantidad, precio ejecución, subtotal, comisión, **producto neto acreditado**.
        - "Tu saldo actual es: USD {newBalance}."
        - Condicional: `<th:if positionDeleted>` "Has liquidado completamente tu posición en {ticker}." `<th:else>` "Tu posición restante en {ticker} es {positionResultingQty} acciones." `</th:if>`
        - Footer estándar.
- ☐ **T3.6** `backend/src/main/resources/templates/email/order-rejected-sell.html` NUEVO. Vars: `{nombreCompleto, ticker, quantity, reason, alpacaReason}`. Mensaje: "Tu orden de venta de {quantity} {ticker} fue rechazada por el mercado: {alpacaReason}. Tu posición no fue modificada y tu saldo no fue afectado."
- ☐ **T3.7** `backend/src/main/resources/templates/email/order-failed-sell.html` NUEVO. Vars: `{nombreCompleto, ticker, quantity, errorMessage}`. Mensaje: "Tu orden de venta de {quantity} {ticker} no pudo procesarse por un error técnico. Tu posición y saldo no fueron afectados. Intenta nuevamente en unos minutos."
- ☐ **T3.8** Si D29 H.5 F09 está implementado (`order-queued.html` existe): crear `order-queued-sell.html` análogo al `-buy`. Mensaje: "Tu orden de venta de {quantity} {ticker} se encoló para ejecutarse en la próxima apertura de mercado. Recibirás el crédito (USD {estimatedProceeds}) al ejecutarse. Tu posición ya se reservó: posición restante {positionResultingQty}."

### OrderEventListener dispatch por side (D7)

- ☐ **T3.9** `trading/event/OrderEventListener.java` MODIFICADO — dispatch por `event.side()`:
    ```java
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderExecuted(OrderExecutedEvent event) {
        Map<String, Object> details = new HashMap<>();
        details.put("orderId", event.orderId());
        details.put("alpacaOrderId", event.alpacaOrderId());  // si está en el event
        details.put("side", event.side().name());
        details.put("executionUnitPrice", event.executionUnitPrice().toPlainString());
        details.put("executionTotal", event.executionTotal().toPlainString());
        details.put("commission", event.commission().toPlainString());
        details.put("positionResultingQty", event.positionResultingQty());
        if (event.positionDeleted() != null) {
            details.put("positionDeleted", event.positionDeleted());
        }
        auditor.emit(AuditEventType.ORDER_EXECUTED, event.userId(), details);

        if (event.side() == OrderSide.BUY) {
            notifier.notifyOrderExecutedBuy(event.userId(), event);
        } else {
            notifier.notifyOrderExecutedSell(event.userId(), event);
        }
    }
    ```
    Análogo para `onOrderRejected` y `onOrderFailed`.

### Tests del Lote C

- ☐ **T3.10** `OrderEventListenerSellDispatchTest.java` — `@SpringBootTest` con `@MockBean Notifier`:
    - `onOrderExecuted_sideSell_invokesNotifyExecutedSell`: publicar `OrderExecutedEvent(side=SELL)` en TX → commit → verify(notifier).notifyOrderExecutedSell(any, any) invocado UNA vez, notifyOrderExecutedBuy NUNCA.
    - `onOrderExecuted_sideBuy_invokesNotifyExecutedBuy`: análogo inverso (anti-regresión).
    - `onOrderRejected_sideSell_invokesNotifyRejectedSell`: idem.
    - `onOrderFailed_sideSell_invokesNotifyFailedSell`: idem.
- ☐ **T3.11** `MailNotifierSellTemplatesTest.java` — opcional (smoke): cada `notifyOrder*Sell` con datos válidos no lanza excepción Thymeleaf (templates renderizan correctamente). Usar GreenMail o `@MockBean JavaMailSender` para no requerir SMTP real.
- ☐ **T3.12** Verificar tests F09 existentes (`OrderEventListenerTest` original) siguen verdes tras rename de métodos Notifier — actualizarlos para usar `notifyOrderExecutedBuy` en lugar de `notifyOrderExecuted`.

### Verificación HITO 3

- ☐ **T3.13** `mvn -f backend/pom.xml -Dtest='OrderEventListenerSellDispatchTest,OrderEventListenerTest' test` verde.
- ☐ **T3.14** `mvn -f backend/pom.xml compile` verde.
- ☐ **T3.15** Inspección manual rápida: `git log --diff-filter=R --summary` muestra los 3-4 rename de templates como `rename` (no add+delete).
- ☐ **T3.16** **← HITO 3 ✅** notificaciones SELL configuradas + dispatch correcto + templates renombradas.

---

## Lote D — Tests IT con WireMock (SELL E2E backend)

> Objetivo: cobertura E2E backend para SELL sin dependencia Alpaca real. Reusa WireMock setup F09. Incluye test específico de lock order BUY+SELL concurrente (D12).

- ☐ **T4.1** `TradingControllerSellIT.java` — `@SpringBootTest(webEnvironment=RANDOM_PORT)` con perfil `test`. WireMock arrancado con `@RegisterExtension` (reusa setup F09). `@BeforeEach` crea usuario + balance + POSICIÓN inicial vía repository directo (no via TradingService, para test fixture limpio):
    ```java
    User user = userRepository.save(...);
    balanceInitializer.initialize(user, new BigDecimal("8000.00"));
    positionRepository.save(Position.newPosition(user.getId(), "AAPL", 10, new BigDecimal("184.62")));
    ```
    - Test 1 `quote_sellHappyPath_returns200WithSufficientShares`: stub data 200 con quote=190 → POST quote SELL × 5 → 200 + body con `sufficientShares=true, userShares=10, estimatedTotal="931.00"`.
    - Test 2 `quote_sellWithoutPosition_returnsSufficientSharesFalse`: borrar la posición pre-test → POST quote SELL × 5 → 200 + `sufficientShares=false, userShares=0`.
    - Test 3 `placeOrder_sellHappyPath_executed_decrementsAndCredits`: stub data + stub trading filled → POST orders SELL × 5 → 201 + assert BD: order EXECUTED + position qty=5 + balance creditado por executionTotal.
    - Test 4 `placeOrder_sellTotalLiquidation_deletesPositionRow`: SELL × 10 (toda la posición) → 201 + position fila NO existe (`positionRepository.findByUserIdAndTicker` retorna empty) + balance creditado.
    - Test 5 `placeOrder_sellShortSelling_returns409_noAlpacaCall`: borrar posición pre-test → POST SELL × 1 → 409 SHORT_SELLING_NOT_ALLOWED + WireMock.verify trading endpoint NUNCA invocado.
    - Test 6 `placeOrder_sellInsufficientShares_returns409_noAlpacaCall`: POST SELL × 50 (más que la posición) → 409 INSUFFICIENT_SHARES + WireMock.verify trading endpoint NUNCA invocado + assert position sigue qty=10.
    - Test 7 `placeOrder_sellAlpacaRejected_returns422_positionUntouched`: stub trading 200 con `status=rejected` → 422 + assert position sigue qty=10 + balance sin cambios + order REJECTED.
    - Test 8 `placeOrder_sellAlpacaDown_returns502_positionUntouched`: stub trading 503 × 3 → 502 + assert position qty=10 + balance sin cambios + order FAILED.
    - Test 9 `placeOrder_sellIdempotency_secondCallReturns200`: ejecutar test 3 happy + segundo POST con mismo clientOrderId → 200 (no 201) + mismo id + Alpaca trading invocado SOLO 1 vez + position decrementada SOLO 1 vez.
- ☐ **T4.2** `TradingServiceSellConcurrencyIT.java` — `@SpringBootTest`:
    - Test 1 `concurrency_twoSellsOverlapPosition_exactlyOneSucceeds`: posición {qty=5}; lanzar 2 placeOrder SELL × 3 concurrentes con clientOrderIds distintos en `CompletableFuture.allOf`. Assert: exactamente 1 retorna EXECUTED + position decrementada por SOLO esa orden (qty=2 final); la otra retorna 409 INSUFFICIENT_SHARES.
    - Test 2 `concurrency_buyAndSellSameTicker_noDeadlock_bothSucceed`: posición {qty=10}, balance 5000; lanzar BUY × 5 + SELL × 5 concurrentes. Assert: ambas retornan EXECUTED (no deadlock); position final qty depende del orden de commit (con lock order D12: BUY incrementa después, SELL decrementa después — orden semánticamente correcto). Verificar que ningún test timeout (síntoma de deadlock).
    - Test 3 `concurrency_tenSellsSameClientId_resultsInOneExecution`: lanzar 10 placeOrder SELL × 3 con MISMO clientOrderId; assert: orderRepository.findByClientOrderId retorna 1, position decrementada 1 vez (qty=7 final desde inicio 10), Alpaca invocado 1 vez.
- ☐ **T4.3** Si tests IT F09 (`TradingControllerIT`, `TradingServiceConcurrencyIT`) tocan métodos renombrados del Notifier — actualizar los mocks/verifies para usar `notifyOrderExecutedBuy` etc.

### Verificación HITO 4

- ☐ **T4.4** `mvn -f backend/pom.xml verify` completo verde (suite F09 + tests nuevos F10 = ~230 tests). Si algún test F09 rompe por rename de Notifier methods (T3.1) o por cambio en `Order.markAsExecuted` side-aware (T2.5) — corregir asserts en el mismo Lote.
- ☐ **T4.5** **← HITO 4 ✅** backend SELL E2E cubierto + concurrencia D12 verificada.

---

## Lote E — Frontend: enable SELL toggle + side-aware wording + 2 códigos messages

> Objetivo: usuario humano puede operar SELL desde `/trade` con UX clara.

### Types + i18n

- ☐ **T5.1** `frontend/src/types/api.ts` MODIFICADO — extender `QuoteResponse`:
    ```typescript
    export interface QuoteResponse {
      ticker: string;
      side: OrderSide;
      quantity: number;
      estimatedUnitPrice: string;
      estimatedSubtotal: string;
      commission: string;
      estimatedTotal: string;  // side-aware
      currency: string;
      userBalance: string;
      sufficientFunds: boolean;
      sufficientShares: boolean;  // NUEVO
      userShares: number;         // NUEVO
      marketOpen: boolean;
      quotedAt: string;
    }
    ```
    `OrderResponse` sin cambios estructurales (semántica side-aware solo documentada en JSDoc).
- ☐ **T5.2** `frontend/src/i18n/messages.es.ts` MODIFICADO — agregar 2 códigos:
    ```typescript
    SHORT_SELLING_NOT_ALLOWED: "No tienes posición en {ticker}. BloomTrade no permite ventas en corto.",
    INSUFFICIENT_SHARES: "Solo tienes {available} {ticker} disponibles para vender (solicitaste {requested}).",
    ```

### Componentes (modificaciones)

- ☐ **T5.3** `frontend/src/features/trading/components/OrderForm.tsx` MODIFICADO:
    - Quitar `disabled` del botón/toggle SELL.
    - Quitar tooltip "Disponible próximamente" del SELL.
    - Cambiar etiqueta del botón principal según side seleccionado: BUY → "Obtener quote de compra"; SELL → "Obtener quote de venta".
    - Opcional: agregar tooltip informativo en el dropdown cuando side=SELL: "Selecciona un ticker que tengas en tu portafolio. El sistema validará al confirmar." (mitiga D10).
- ☐ **T5.4** `frontend/src/features/trading/components/OrderQuotePanel.tsx` MODIFICADO — branching por `quote.side`:
    - **BUY**: igual que F09. Labels: "Total a pagar", "Saldo después" (saldo decrementado).
    - **SELL**:
        - "Producto neto a recibir: USD {estimatedTotal}" (en lugar de "Total a pagar").
        - "Saldo actual: USD {userBalance} → Saldo después: USD {userBalance + estimatedTotal}" (saldo incrementado — calcular client-side con BigDecimal.js o simple suma de strings precaución; alternativa: backend devolver `newBalanceAfter` campo extra — **decisión MVP**: calcular client-side aceptando el riesgo de precision drift menor).
        - **Línea nueva** condicional:
            - Si `quote.userShares > quote.quantity`: "Posición restante: {userShares - quantity} {ticker}".
            - Si `quote.userShares === quote.quantity`: "Esta venta liquidará tu posición completa en {ticker}."
        - Si `sufficientShares === false`:
            - Mensaje en rojo con CTA deshabilitado:
                - `userShares === 0`: "No tienes posición en {ticker}. Compra primero para poder vender."
                - `userShares > 0`: "Solo tienes {userShares} {ticker} disponibles para vender. Reduce la cantidad."
            - Botón "Confirmar venta" deshabilitado.
        - Wording del CTA: "Confirmar venta" (no "Confirmar compra").
- ☐ **T5.5** `frontend/src/features/trading/components/OrderConfirmationToast.tsx` MODIFICADO — branching por `order.side`:
    - **BUY** (heredado): "✅ Compraste {quantity} {ticker} a USD {executionUnitPrice}" + palette emerald.
    - **SELL** (nuevo): "✅ Vendiste {quantity} {ticker} a USD {executionUnitPrice} — recibiste USD {executionTotal}" + palette emerald.
    - **PENDING+alpacaOrderId** (D9 heredado): "⏳ Tu orden de venta se encoló — recibirás el crédito al ejecutarse" + palette ámbar.
    - Si la venta liquidó la posición: agregar línea adicional "Posición liquidada — el ticker ya no aparece en tu portafolio."

### Hook (manejo de error)

- ☐ **T5.6** `frontend/src/features/trading/hooks/useSubmitOrder.ts` MODIFICADO — extender el `onError` para los 2 códigos nuevos:
    ```typescript
    onError: (error: ApiError) => {
      switch (error.code) {
        case "SHORT_SELLING_NOT_ALLOWED":
          showError(t("SHORT_SELLING_NOT_ALLOWED", { ticker: error.details?.ticker }));
          break;
        case "INSUFFICIENT_SHARES":
          showError(t("INSUFFICIENT_SHARES", {
            available: error.details?.available,
            ticker: error.details?.ticker,
            requested: error.details?.requested,
          }));
          break;
        // ... resto de cases heredados F09 (INSUFFICIENT_FUNDS, ALPACA_API_ERROR, etc.) ...
      }
    }
    ```

### Tests frontend

- ☐ **T5.7** Saltados [[feedback-coverage-vs-velocidad]] — mismo criterio HU-F04/F06/F09 Lote H. Se valida en HITO 5 humano.

### Verificación HITO 5

- ☐ **T5.8** `cd frontend && npm run build` verde.
- ☐ **T5.9** `cd frontend && npm run typecheck` verde (sin errores TS del cambio en QuoteResponse).
- ☐ **T5.10** **Demo manual del humano** (idealmente con NYSE abierto — martes 26-May 2026 8:30 AM hora COL en adelante):
    - `docker compose up -d --build`.
    - Login con usuario test que tenga al menos 1 posición (de F09 demo o ejecutar BUY × 5 primero).
    - Navegar `/trade` → activar toggle SELL → seleccionar ticker en posición → ingresar quantity ≤ posición.
    - Click "Obtener quote de venta" → panel muestra "Producto neto a recibir: USD X" + "Saldo después: USD Y" + línea de posición restante.
    - Click "Confirmar venta" → toast emerald "✅ Vendiste — recibiste USD X".
    - Abrir MailHog (`localhost:8025`) → verificar email `order-executed-sell.html` con wording correcto y producto neto.
    - Abrir Kibana (`localhost:5601`) → verificar evento `ORDER_EXECUTED` con `details.side="SELL"`, `details.positionResultingQty=N`, `details.positionDeleted=true|false`.
    - Verificar Alpaca dashboard (https://app.alpaca.markets/paper/dashboard/overview) → la venta aparece registrada.
- ☐ **T5.11** Smoke con `psql`:
    ```sql
    SELECT id, ticker, side, status, execution_unit_price, execution_total
      FROM app.orders WHERE side='SELL' ORDER BY submitted_at DESC LIMIT 1;
    SELECT balance FROM app.user_balances WHERE user_id = '...';
    SELECT ticker, quantity, avg_buy_price FROM app.positions WHERE user_id = '...';
    ```
    Confirmar: order SELL EXECUTED, balance incrementado por execution_total, position decrementada o fila borrada.
- ☐ **T5.12** **Smoke negativo**: intentar SELL de un ticker SIN posición → toast rojo "No tienes posición en {ticker}". Intentar SELL con quantity > posición → toast rojo "Solo tienes {N} disponibles".
- ☐ **T5.13** **← HITO 5 ✅** frontend funcional + E2E manual confirmado.

---

## Lote F — Cierre: APRENDIZAJES + AGENTS.md handoff + commit ready

> Objetivo: documentación final. Commit preparado para firma humana. SPEC v1.1 si emergieron decisiones nuevas.

### Documentación

- ☐ **T6.1** Revisar si emergieron decisiones nuevas durante implementación (Lotes A–E). Patrón F09 D23–D29: emerging decisions van en `plan.md` §2.3 (o nueva sección §2.4). Probables candidatas:
    - DXX: separación `validateSellable` / `decrementPosition` (T2.4) — patrón emergente para mantener semántica "valida antes de Alpaca, decrementa después".
    - DXX: orden de lock pessimistic exacto (D12 refinado): "SELL toma SOLO el lock de positions; BUY toma SOLO el lock de balances; no se cruzan en la misma operación, evitando deadlock natural." Reformular D12 si fue impreciso.
    - DXX: cálculo client-side de "saldo después" en SELL (T5.4 alternativa rechazada).
- ☐ **T6.2** Si emergieron D17+, MODIFICAR `specs/HU-F10-orden-venta-market/plan.md` agregando §2.4 "Decisiones emergentes durante implementación (D17–Dxx)" con cada una documentada (formato F09 D23–D27).
- ☐ **T6.3** Si emergieron decisiones que afectan el SPEC, MODIFICAR `specs/HU-F10-orden-venta-market/SPEC.md` bump a v1.1:
    - §1 Metadatos: `Versión spec` = `1.1`, `Última actualización` = fecha real.
    - Secciones afectadas: surgicalmente reescritas.
    - Changelog: nueva fila v1.1 con razón.
- ☐ **T6.4** `APRENDIZAJES.md` MODIFICADO — agregar sección "Día 7 — HU-F10 Venta Market" en primera persona con ~5-7 reflexiones. Áreas potenciales:
    - **Validación retrospectiva del andamio F09**: F10 reusó ~60% del código y solo agregó 2 excepciones + 2 métodos PortfolioService + 3 templates email + dispatch lógico. El SPEC F09 anticipó F10 (chk_order_side BUY|SELL, chk_position_quantity >=0) correctamente — buena planeación SDD.
    - **Complejidad real vs estimada**: estimación AGENTS.md fue ~40-50% del esfuerzo F09. ¿Salió ese rango? Si fue mayor (60-70%) reflexionar dónde sub-estimamos (probablemente el branching side-aware en `Order.markAsExecuted` o el rename de templates F09 cuyo blast radius en tests rompió cosas inesperadas).
    - **Lock pessimistic en BUY+SELL concurrente**: cómo el D12 (orden de adquisición) evitó deadlocks. Test concurrencia D-12 fue valioso.
    - **D29 F09 heredado en SELL con riesgo asimétrico**: la decisión "decrementar posición optimistamente" en encoladas tiene riesgo edge case (Alpaca cancela → usuario pierde posición sin crédito). Documentado como deuda; probabilidad muy baja en testing single-user.
    - **Rename de F09 templates a sufijo `-buy`**: cuánto rompió en tests existentes; cómo `git mv` preservó history; lesson aprendida sobre simetría de naming en interfaces.
    - **DELETE de fila en `app.positions` cuando qty=0**: por qué fue la decisión correcta sobre soft-delete. HU-F16 será más limpia gracias a esto.
- ☐ **T6.5** `AGENTS.md` MODIFICADO — actualizar sección "Trabajo activo":
    - Branch: `main` (HU-F10 mergeada — PR #X, fecha)
    - HU activa: Ninguna (próximo: HU-F16 + HU-F21 Día 8 bundle portafolio + saldo)
    - Sprint: 2 en curso, HU-F10 cerrada, próximo Día 8
    - Próximo paso: bundle HU-F16 + HU-F21 (portafolio + saldo). Reutiliza completamente PortfolioService de F09+F10 — solo GET endpoints + DTOs + frontend.
    - Deuda viva: extender con (1) reconciliation SELL encoladas (D9 D-SELL-QUEUED-RISK), (2) `TickerDropdown` filtrar por posiciones cuando F16 mergee (D10 D-UI-FILTER-SELL-DROPDOWN), (3) demás heredadas de F09.
    - Sección "Cómo continuar": handoff post HU-F10 → arranque HU-F16+F21 Día 8 con lista de pre-requisitos.

### Commit

- ☐ **T6.6** Preparar mensaje de commit en `C:\Users\juang\AppData\Local\Temp\bt-hu-f10.txt` [[feedback-commit-file-ruta-completa]]:
    ```
    feat(trading): cierra HU-F10 — orden de venta Market con Alpaca paper trading

    Completa el flujo de trading bidireccional del MVP reutilizando el andamio
    de HU-F09 (~60% del código). Agrega:
    - PortfolioService.credit + decrementPosition (DELETE en qty=0).
    - 2 validaciones server-side: SHORT_SELLING_NOT_ALLOWED, INSUFFICIENT_SHARES.
    - TradingService dispatch interno por side (D5 D-TRADING-METHOD).
    - 3 métodos Notifier *Sell + rename de F09 templates a sufijo -buy.
    - QuoteResponse extendido con sufficientShares + userShares (retro-compatible).
    - executionTotal con semántica side-aware (BUY=cobrado, SELL=neto acreditado).
    - Frontend: toggle SELL habilitado, wording side-aware en QuotePanel/Toast.
    - Tests IT con WireMock cubren happy SELL, short selling, insufficient shares,
      Alpaca down/reject, idempotencia, concurrencia SELL×2 y BUY+SELL concurrente.

    Hereda D29 F09 en SELL: si Alpaca responde 'accepted' (mercado cerrado), la
    orden queda PENDING+alpacaOrderId y la posición se decrementa optimistamente
    (D9 D-SELL-QUEUED-RISK documentado como deuda en plan.md).

    refs HU-F10 specs/HU-F10-orden-venta-market/SPEC.md

    Co-authored-by: Claude <noreply@anthropic.com>
    ```

### Verificación HITO 6

- ☐ **T6.7** `mvn -f backend/pom.xml verify` final verde en la branch.
- ☐ **T6.8** `cd frontend && npm run build` final verde.
- ☐ **T6.9** Documentos maestros consistentes con el código (revisión manual rápida — STACK.md y ARCHITECTURE.md no requieren cambios; solo SPEC y plan si emergieron decisiones).
- ☐ **T6.10** `git status` muestra todos los archivos modificados/nuevos esperados.
- ☐ **T6.11** **← HITO 6 ✅** todo listo para `git add` + `git commit -F C:\Users\juang\AppData\Local\Temp\bt-hu-f10.txt` + `git push -u origin feat/HU-F10-orden-venta-market` + PR del humano.

---

## Resumen de archivos a crear/modificar

**Backend nuevos (~7 archivos):**
- `trading/exception/ShortSellingNotAllowedException.java`
- `trading/exception/InsufficientSharesException.java`
- 4 templates Thymeleaf: `order-executed-sell.html`, `order-rejected-sell.html`, `order-failed-sell.html`, (opcional) `order-queued-sell.html`
- `TradingControllerSellIT.java`, `TradingServiceSellConcurrencyIT.java` (tests IT)
- Tests unitarios: `PortfolioServiceCreditTest.java`, `PortfolioServiceDecrementPositionTest.java`, `TradingServiceQuoteSellTest.java`, `TradingServicePlaceOrderSellTest.java`, `OrderEventListenerSellDispatchTest.java`, `OrderMarkAsExecutedSideAwareTest.java`

**Backend modificados (~10 archivos):**
- `portfolio/domain/Position.java` (+`decrementBy`, +`isDepleted`)
- `portfolio/repository/PositionRepository.java` (+lock pessimistic + delete)
- `portfolio/service/PortfolioService.java` (+`credit`, +`decrementPosition`, +`validateSellable`, +`findPosition`)
- `trading/domain/Order.java` (+`markAsExecuted` side-aware)
- `trading/service/TradingService.java` (+rama SELL en quote + dispatch en placeOrderTx + `handleSellTx`)
- `trading/dto/QuoteResponse.java` (+`sufficientShares`, +`userShares`)
- `trading/dto/OrderResponse.java` (@Schema descriptions side-aware)
- `trading/event/OrderExecutedEvent.java` (+`positionResultingQty`, +`positionDeleted`)
- `trading/event/OrderEventListener.java` (dispatch por side)
- `notification/Notifier.java` (+3 métodos `*Sell` + rename 3 a `*Buy`)
- `notification/MailNotifier.java` (implementar 3 + actualizar 3 renombrados)
- `shared/web/GlobalExceptionHandler.java` (+2 handlers 409)
- `shared/web/validation-messages.properties` (+2 códigos)

**Backend renombrados (D6):**
- `templates/email/order-executed.html` → `order-executed-buy.html`
- `templates/email/order-rejected.html` → `order-rejected-buy.html`
- `templates/email/order-failed.html` → `order-failed-buy.html`
- `templates/email/order-queued.html` → `order-queued-buy.html` (si existe — D29 F09 H.5)

**Backend tests F09 a actualizar** (impactados por renames):
- Tests que asserten `notifyOrderExecuted` etc. → `notifyOrderExecutedBuy`.
- Tests que asserten path de template → path con sufijo `-buy`.

**Frontend modificados (~5 archivos):**
- `types/api.ts` (+`sufficientShares`, +`userShares` en QuoteResponse)
- `i18n/messages.es.ts` (+2 códigos)
- `features/trading/components/OrderForm.tsx` (enable SELL toggle)
- `features/trading/components/OrderQuotePanel.tsx` (branching side-aware)
- `features/trading/components/OrderConfirmationToast.tsx` (branching side-aware)
- `features/trading/hooks/useSubmitOrder.ts` (manejo 2 códigos nuevos)

**Frontend NUEVOS:** ninguno (reuso completo de componentes F09).

**Migración Flyway:** ninguna (D13 verificado).

**Total estimado**: ~13 archivos nuevos + ~15 modificados + 4 renombrados. **~50% del tamaño de F09** (consistente con AGENTS.md "~40-50% del esfuerzo").

---

## Notas finales para la sesión de implementación

1. **Pre-Lote A**: ejecutar T1.1–T1.4 (verificación V5) antes de tocar código. Si falla → V6 correctiva.
2. **Pre-HITO 5 demo**: idealmente con NYSE abierto (martes 26-May 8:30 AM COL en adelante post-Memorial Day). Si NYSE cerrado, los HITOs 1–4 (backend + unit/IT) siguen siendo válidos; HITO 5 puede postergarse 1 día.
3. **Pre-HITO 5 setup**: tener al menos 1 posición en `app.positions` del usuario testing. Si la posición de F09 demo quedó `PENDING+alpacaOrderId` (mercado cerrado el día del demo F09), ejecutar primero BUY × 5 AAPL en `/trade` con mercado abierto.
4. **Tests con perfil `test`**: respetar D16 HU-F01 — Postgres en `localhost:5433/bloomtrade_test`, NO Testcontainers.
5. **Cadencia de validación** [[feedback-cadencia-sdd]]: commit-prep al cierre de cada lote, NO entre tareas. Validación humana en cada HITO. NO micro-checkpoint tras cada T1.x.
6. **Skip de tests frontend** [[feedback-coverage-vs-velocidad]]: P1 — documentado como deuda análoga a F04/F06/F09.
7. **Coverage target ~60-65%** [[feedback-coverage-vs-velocidad]]: foco crítico en PortfolioService.credit/decrement (BigDecimal exact + DELETE behavior), dispatch SELL en TradingService, OrderEventListener side-aware. Skip tests de mappers + DTOs triviales.
8. **Documentar emergentes** en plan.md §2.3 (no en chat). Si emerge algo grande (>3 LOC de impacto), actualizar SPEC v1.1 en Lote F.
