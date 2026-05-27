# tasks.md — HU-F15 Cancelar orden Market

> Descomposición granular del `plan.md` v1.0 (SDD Paso 3).
> Cadencia: lotes lógicos A–F con validación en HITOs [[feedback-cadencia-sdd]].
> Rama: `feat/HU-F15-cancelar-orden` (ya creada desde main `3d8c3be`).
> Commits con `refs HU-F15 specs/HU-F15-cancelar-orden/SPEC.md` + `Co-authored-by: Claude <noreply@anthropic.com>`.

Leyenda: ☐ pendiente · ◐ en progreso · ☑ hecho · ✗ cancelado/diferido

---

## Lote A — Backend infraestructura: enum + V6 + adapter + tests unit (HITO 1)

> Objetivo: levantar el andamio backend mínimo para que las capas superiores tengan dónde apoyarse. NO toca `TradingService` ni `OrderReconciliationService` aún — eso es Lote B/C.

### Verificación pre-coding (D23)

- ☐ **T1.1** `docker compose up -d postgres redis` (si no están corriendo). Verificar `docker ps`.
- ☐ **T1.2** `docker compose exec postgres psql -U bloomtrade -d bloomtrade -c "\d app.orders"` — confirmar que las 3 columnas NUEVAS NO existen aún (`cancel_requested_at`, `canceled_at`, `expired_at`, `avg_buy_price_at_submission`). Si alguna existe → STOP, investigar.
- ☐ **T1.3** `docker compose exec postgres psql -U bloomtrade -d bloomtrade -c "SELECT constraint_name, check_clause FROM information_schema.check_constraints WHERE constraint_name = 'chk_order_status';"` — confirmar `chk_order_status` actual lista `(PENDING, EXECUTED, REJECTED, FAILED)`. Anotar el output exacto para construir V6.
- ☐ **T1.4** `docker compose exec postgres psql -U bloomtrade -d bloomtrade -c "SELECT status, COUNT(*) FROM app.orders GROUP BY status;"` — registrar baseline de órdenes existentes (útil después para verificar que V6 no rompe ninguna fila histórica).
- ☐ **T1.5** `docker compose exec postgres psql -U bloomtrade -d bloomtrade -c "SELECT id, side, status, alpaca_order_id FROM app.orders WHERE status='PENDING' AND alpaca_order_id IS NOT NULL ORDER BY submitted_at DESC LIMIT 5;"` — verificar si hay PENDING+alpacaOrderId disponibles para tests + HITO 5 smoke. Si no hay → registrar como pre-requisito demo HITO 5.

### Migración V6

- ☐ **T1.6** `backend/src/main/resources/db/migration/V6__add_canceled_expired_status_and_cancel_columns.sql` NUEVO:
    ```sql
    -- V6: Cancelación de órdenes (HU-F15)
    -- Aditiva, idempotente, sin DDL destructivo. Backfill SELL queued legacy con avg_buy_price_at_submission.

    ALTER TABLE app.orders DROP CONSTRAINT IF EXISTS chk_order_status;
    ALTER TABLE app.orders ADD CONSTRAINT chk_order_status
      CHECK (status IN ('PENDING', 'EXECUTED', 'REJECTED', 'FAILED', 'CANCELED', 'EXPIRED'));

    ALTER TABLE app.orders ADD COLUMN IF NOT EXISTS cancel_requested_at TIMESTAMPTZ NULL;
    ALTER TABLE app.orders ADD COLUMN IF NOT EXISTS canceled_at TIMESTAMPTZ NULL;
    ALTER TABLE app.orders ADD COLUMN IF NOT EXISTS expired_at TIMESTAMPTZ NULL;
    ALTER TABLE app.orders ADD COLUMN IF NOT EXISTS avg_buy_price_at_submission NUMERIC(19,4) NULL;

    -- Backfill SELL legacy (R3 mitigation): si hay SELLs queued pre-V6, snapshot del quoted_unit_price como fallback.
    UPDATE app.orders
       SET avg_buy_price_at_submission = quoted_unit_price
     WHERE side = 'SELL'
       AND avg_buy_price_at_submission IS NULL;

    -- Índice parcial para acelerar reconcile lazy v2 (busca PENDING+cancelRequestedAt para materializar).
    CREATE INDEX IF NOT EXISTS idx_orders_cancel_requested_at
      ON app.orders (cancel_requested_at)
      WHERE cancel_requested_at IS NOT NULL AND status = 'PENDING';
    ```
- ☐ **T1.7** Verificar la migración aplicable en perfil test: `docker compose exec postgres psql -U bloomtrade -d bloomtrade_test -f /tmp/V6.sql` (copy + dry-run). Si falla por alguna razón (constraint en uso por filas existentes) → ajustar y documentar como decisión emergente D25.

### Enum OrderStatus + Order entity extension

- ☐ **T1.8** `backend/src/main/java/.../trading/domain/OrderStatus.java` MODIFICADO — agregar 2 valores nuevos al final del enum: `CANCELED`, `EXPIRED`. Mantener el orden existente (PENDING, EXECUTED, REJECTED, FAILED, CANCELED, EXPIRED).
- ☐ **T1.9** `trading/domain/Order.java` MODIFICADO — agregar 4 campos:
    ```java
    @Column(name = "cancel_requested_at")
    private Instant cancelRequestedAt;

    @Column(name = "canceled_at")
    private Instant canceledAt;

    @Column(name = "expired_at")
    private Instant expiredAt;

    @Column(name = "avg_buy_price_at_submission", precision = 19, scale = 4)
    private BigDecimal avgBuyPriceAtSubmission;
    ```
    + 3 métodos de dominio:
    ```java
    public void markCancelRequested() {
        if (this.status != OrderStatus.PENDING) {
            throw new IllegalStateException("Cannot mark cancel requested on " + this.status);
        }
        this.cancelRequestedAt = Instant.now();
    }

    public void markCanceled(Instant alpacaCanceledAt) {
        if (this.status != OrderStatus.PENDING) {
            throw new IllegalStateException("Cannot cancel order in " + this.status);
        }
        this.status = OrderStatus.CANCELED;
        this.canceledAt = Instant.now();
        // alpacaCanceledAt field exists in F09 V5 if there's alpaca_canceled_at column — verificar T1.10.
    }

    public void markExpired() {
        if (this.status != OrderStatus.PENDING) {
            throw new IllegalStateException("Cannot expire order in " + this.status);
        }
        this.status = OrderStatus.EXPIRED;
        this.expiredAt = Instant.now();
    }

    public boolean isCancelable() {
        return this.status == OrderStatus.PENDING && this.alpacaOrderId != null;
    }
    ```
- ☐ **T1.10** Verificar existencia del campo `alpaca_canceled_at` en `app.orders` (F09 V5). Si existe → mapear en `Order.java`. Si no → registrar como decisión emergente (probable D25 o D26): se omite por scope o se agrega a V6.

### CancelOutcome sealed type (D24)

- ☐ **T1.11** `trading/dto/CancelOutcome.java` NUEVO (sealed interface Java 21):
    ```java
    public sealed interface CancelOutcome
        permits CancelOutcome.Canceled,
                CancelOutcome.PendingCancel,
                CancelOutcome.RaceFilled {

        record Canceled(Instant alpacaCanceledAt) implements CancelOutcome {}

        record PendingCancel(String reason) implements CancelOutcome {}

        record RaceFilled(BigDecimal filledAvgPrice, int filledQty, Instant alpacaFilledAt) implements CancelOutcome {}
    }
    ```

### AlpacaTradingAdapter.cancelOrder

- ☐ **T1.12** `integration/trading/AlpacaTradingAdapter.java` MODIFICADO — agregar método `cancelOrder(String alpacaOrderId): CancelOutcome`:
    - `DELETE /v2/orders/{alpacaOrderId}` envuelto en `@Retry(name="alpacaApi")`. Esperar 204 (success) o capturar 404 / 422.
    - Si Alpaca devuelve 404 → lanzar `AlpacaOrderNotFoundException(alpacaOrderId)`. NO catch interno — propagar (TradingService.cancelOrder lo maneja como drift detected).
    - Si Alpaca devuelve 422 → lanzar `AlpacaOrderNotCancelableException(alpacaOrderId)`. Idem propagar.
    - Polling loop (configuración D-POLLING-CONFIG plan.md): `for (int i = 0; i < maxAttempts; i++) { Thread.sleep(intervalMs); GET /v2/orders/{alpacaOrderId}; ... }`.
    - Parse del response del GET: switch sobre `status` field. Si `canceled` → `CancelOutcome.Canceled(canceledAt)`. Si `filled` → `CancelOutcome.RaceFilled(filledAvgPrice, filledQty, filledAt)`. Si `partially_filled` → lanzar `AlpacaUnexpectedStatusException` (D19). Si `accepted`/`new`/`pending_cancel` → continuar loop. Si `expired`/`rejected` → propagar como `AlpacaUnexpectedStatusException` (no esperado durante un cancel inmediato).
    - Si polling agota sin estado terminal → `CancelOutcome.PendingCancel("POLLING_TIMEOUT_2S")`.
- ☐ **T1.13** `integration/trading/exception/AlpacaOrderNotFoundException.java` NUEVO — `RuntimeException` extends `AlpacaApiException` (heredado F09). Campo: `alpacaOrderId`.
- ☐ **T1.14** `integration/trading/exception/AlpacaOrderNotCancelableException.java` NUEVO — `RuntimeException` extends `AlpacaApiException`. Campo: `alpacaOrderId`. Mensaje: "Alpaca rechazó el cancel: orden ya filled o no cancelable".
- ☐ **T1.15** `integration/trading/exception/AlpacaUnexpectedStatusException.java` NUEVO — `RuntimeException` extends `AlpacaApiException`. Campos: `alpacaOrderId`, `unexpectedStatus`. Para `partially_filled` y otros statuses no contemplados.

### Configuración

- ☐ **T1.16** `backend/src/main/resources/application.yml` MODIFICADO — agregar bloque:
    ```yaml
    trading:
      cancel:
        polling:
          interval-ms: 200
          max-attempts: 10  # = 2s timeout total
    ```
- ☐ **T1.17** `backend/src/test/resources/application-test.yml` MODIFICADO — override para velocidad tests:
    ```yaml
    trading:
      cancel:
        polling:
          interval-ms: 10
          max-attempts: 5
    ```
- ☐ **T1.18** `AlpacaTradingAdapter` consume las 2 configs via `@Value("${trading.cancel.polling.interval-ms}")` y `@Value("${trading.cancel.polling.max-attempts}")`.

### AuditEventType extensions

- ☐ **T1.19** `audit/domain/AuditEventType.java` MODIFICADO — agregar 6 entries:
    ```java
    ORDER_CANCEL_REQUESTED,
    ORDER_CANCELED,
    ORDER_EXPIRED,
    ORDER_DUPLICATE_CANCEL_REQUEST,
    ORDER_CANCEL_REJECTED,
    ORDER_CANCEL_FAILED,
    ```

### Tests unit del Lote A

- ☐ **T1.20** `AlpacaTradingAdapterCancelTest.java` NUEVO — con `MockRestServiceServer`:
    - `cancelOrder_happyPath_returnsCanceledQuickly`: DELETE 204 + GET 1 attempt devuelve `status=canceled` → `CancelOutcome.Canceled`.
    - `cancelOrder_pollingTimeout_returnsPendingCancel`: DELETE 204 + GET siempre devuelve `accepted` por 10 attempts → `CancelOutcome.PendingCancel("POLLING_TIMEOUT_2S")`. **Con override application-test.yml (10ms × 5 = 50ms total).**
    - `cancelOrder_raceFilledDuringPoll_returnsRaceFilled`: DELETE 204 + GET attempt #3 devuelve `status=filled` con `filled_avg_price=198.50` y `filled_qty=5` → `CancelOutcome.RaceFilled(198.50, 5, ...)`.
    - `cancelOrder_alpacaReturns404_throwsOrderNotFound`: DELETE 404 → `AlpacaOrderNotFoundException`.
    - `cancelOrder_alpacaReturns422_throwsOrderNotCancelable`: DELETE 422 → `AlpacaOrderNotCancelableException`.
    - `cancelOrder_pollingPartiallyFilled_throwsUnexpectedStatus`: DELETE 204 + GET devuelve `status=partially_filled` → `AlpacaUnexpectedStatusException`.
- ☐ **T1.21** `OrderTest.java` (nuevo si no existe, o extensión) — verificar los 3 métodos de dominio:
    - `markCanceled_onPending_setsStatusAndTimestamp`.
    - `markCanceled_onExecuted_throwsIllegalState`.
    - `markExpired_onPending_setsStatusAndTimestamp`.
    - `markCancelRequested_onPending_setsTimestamp`.
    - `isCancelable_pendingWithAlpacaId_returnsTrue`.
    - `isCancelable_pendingWithoutAlpacaId_returnsFalse`.
    - `isCancelable_executed_returnsFalse`.

### Validación HITO 1

- ☐ **T1.22** `cd backend && ./mvnw clean compile test-compile` — todo compila.
- ☐ **T1.23** `./mvnw flyway:info` — V6 listada como `Pending`.
- ☐ **T1.24** `./mvnw test -Dtest='AlpacaTradingAdapterCancelTest,OrderTest'` — los 13 tests unit del Lote A verdes.
- ☐ **T1.25** `./mvnw flyway:migrate -Dflyway.configFiles=src/main/resources/flyway.conf` — V6 aplica en `bloomtrade` (DB principal). Re-correr `psql ... -c "\d app.orders"` — verificar 4 columnas nuevas + `chk_order_status` con 6 valores.

**HITO 1 criterio:** compile + unit tests del Lote A verdes + V6 aplicada en DB. No se ha tocado `TradingService` ni `OrderController` aún.

---

## Lote B — Backend service: TradingService.cancelOrder + tests unit (HITO 2)

> Objetivo: implementar el endpoint REST + el método `cancelOrder` del service con dispatch BUY/SELL, lock canónico, idempotency, audit, listener post-commit, email. NO tocar `OrderReconciliationService` aún — eso es Lote C.

### OrderRepository extension

- ☐ **T2.1** `trading/repository/OrderRepository.java` MODIFICADO — agregar método:
    ```java
    Optional<Order> findByIdAndUserId(UUID id, UUID userId);
    ```
    Spring Data deriva la query automáticamente. Defensa anti-enumeración (D del SPEC §5.3.1).

### DTOs

- ☐ **T2.2** `trading/dto/OrderResponse.java` MODIFICADO — agregar 4 campos:
    ```java
    @Schema(description = "Timestamp en que la orden quedó CANCELED. NULL si status != CANCELED.")
    private String canceledAt;  // ISO-8601, nullable

    @Schema(description = "Timestamp del cancel solicitado pero aún no materializado (polling-timeout). Coexiste con status=PENDING.")
    private String cancelRequestedAt;  // nullable

    @Schema(description = "Timestamp en que la orden EXPIRED. NULL si status != EXPIRED.")
    private String expiredAt;  // nullable

    @Schema(description = "Solo BUY canceladas/expiradas: monto refundido al balance (string para precisión BigDecimal).")
    private String refundedAmount;  // nullable

    @Schema(description = "Solo SELL canceladas/expiradas: cantidad restaurada a la posición.")
    private Integer restoredQty;  // nullable
    ```
- ☐ **T2.3** `trading/mapper/OrderMapper.java` MODIFICADO — extender `toResponse(Order)` para poblar los 4 campos cuando aplique. Helper privados `mapInstantToString(Instant)`.

### Events nuevos

- ☐ **T2.4** `trading/event/OrderCanceledEvent.java` NUEVO (record):
    ```java
    public record OrderCanceledEvent(
        UUID orderId,
        UUID userId,
        OrderSide side,
        String ticker,
        int quantity,
        String alpacaOrderId,
        BigDecimal refundedAmount,  // BUY: quoted_total; SELL: null (no refund de balance, restore de qty)
        Integer restoredQty,         // SELL: cantidad restaurada; BUY: null
        Instant canceledAt,
        CancelSource source          // USER_REQUEST | BROKER_CANCEL | DRIFT_RECONCILE
    ) {
        public enum CancelSource { USER_REQUEST, BROKER_CANCEL, DRIFT_RECONCILE }
    }
    ```
- ☐ **T2.5** `trading/event/OrderExpiredEvent.java` NUEVO (record) — análogo:
    ```java
    public record OrderExpiredEvent(
        UUID orderId,
        UUID userId,
        OrderSide side,
        String ticker,
        int quantity,
        String alpacaOrderId,
        BigDecimal refundedAmount,
        Integer restoredQty,
        Instant expiredAt
    ) {}
    ```
- ☐ **T2.6** `trading/event/OrderCancelPendingEvent.java` NUEVO (record) — info-only:
    ```java
    public record OrderCancelPendingEvent(
        UUID orderId,
        UUID userId,
        OrderSide side,
        String ticker,
        Instant cancelRequestedAt
    ) {}
    ```

### Excepciones nuevas

- ☐ **T2.7** `trading/exception/OrderNotFoundException.java` NUEVO — `RuntimeException`. Campo `orderId`. Mensaje genérico: `"No se encontró la orden solicitada"` (NO incluir orderId en el mensaje al cliente — anti-enumeración).
- ☐ **T2.8** `trading/exception/OrderNotCancelableException.java` NUEVO — `RuntimeException`. Campos: `orderId`, `currentStatus`. Mensaje: `"Order in status " + currentStatus + " is not cancelable"`.

### GlobalExceptionHandler

- ☐ **T2.9** `shared/web/GlobalExceptionHandler.java` MODIFICADO — agregar 2 handlers:
    ```java
    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleOrderNotFound(OrderNotFoundException ex) {
        return errorResponse(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", null);
    }

    @ExceptionHandler(OrderNotCancelableException.class)
    public ResponseEntity<ErrorResponse> handleOrderNotCancelable(OrderNotCancelableException ex) {
        Map<String, Object> details = Map.of("currentStatus", ex.getCurrentStatus().name());
        return errorResponse(HttpStatus.CONFLICT, "ORDER_NOT_CANCELABLE", details);
    }
    ```
    El 502 `BROKER_UNAVAILABLE` reusa el handler existente de `AlpacaApiException` heredado F09 (verificar que el código de error mapeado sea correcto — si actualmente devuelve `ALPACA_API_ERROR`, cambiar a `BROKER_UNAVAILABLE` o registrar como decisión emergente).

### ValidationMessages

- ☐ **T2.10** `shared/web/ValidationMessages.java` MODIFICADO — agregar 3 keys:
    ```java
    public static final String ORDER_NOT_FOUND = "ORDER_NOT_FOUND";
    public static final String ORDER_NOT_CANCELABLE = "ORDER_NOT_CANCELABLE";
    public static final String BROKER_UNAVAILABLE = "BROKER_UNAVAILABLE";
    ```
    Y entries en el `messages.properties` (i18n).

### TradingService.cancelOrder

- ☐ **T2.11** `trading/service/TradingService.java` MODIFICADO — agregar método principal:
    ```java
    @Transactional(noRollbackFor = {
        OrderNotFoundException.class,
        OrderNotCancelableException.class,
        AlpacaApiException.class  // para que el audit ORDER_CANCEL_FAILED se preserve
    })
    public OrderResponse cancelOrder(UUID userId, UUID orderId) {
        // 1. Lock canónico balances → positions (D17 F10)
        UserBalance balance = userBalanceRepository.findByUserIdForUpdate(userId)
            .orElseThrow(() -> new IllegalStateException("Balance not found for userId=" + userId));

        // 2. Buscar orden con defensa anti-enumeración
        Order order = orderRepository.findByIdAndUserId(orderId, userId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));

        // 3. Idempotencia + estado check
        if (order.getStatus() == OrderStatus.CANCELED) {
            // Short-circuit idempotency: 2da call sobre CANCELED → 200 con alreadyCanceledAt
            auditor.emit(AuditEventType.ORDER_DUPLICATE_CANCEL_REQUEST, ...);
            return orderMapper.toResponse(order);
        }
        if (order.getCancelRequestedAt() != null) {
            // Re-request sobre polling-timeout: 200 con cancelRequestedAt existente, NO segundo DELETE
            auditor.emit(AuditEventType.ORDER_DUPLICATE_CANCEL_REQUEST, ...);
            return orderMapper.toResponse(order);
        }
        if (!order.isCancelable()) {
            // EXECUTED / REJECTED / FAILED / EXPIRED → 409
            auditor.emit(AuditEventType.ORDER_CANCEL_REJECTED, ...);
            throw new OrderNotCancelableException(orderId, order.getStatus());
        }

        // 4. Emit audit ORDER_CANCEL_REQUESTED
        auditor.emit(AuditEventType.ORDER_CANCEL_REQUESTED, Map.of(...));

        // 5. Llama Alpaca cancel
        CancelOutcome outcome;
        try {
            outcome = alpacaAdapter.cancelOrder(order.getAlpacaOrderId());
        } catch (AlpacaOrderNotFoundException | AlpacaOrderNotCancelableException ex) {
            // Drift detected — reconcile inline (lazy v2 path)
            return applyDriftReconcile(order);
        } catch (AlpacaApiException ex) {
            // Broker down
            auditor.emit(AuditEventType.ORDER_CANCEL_FAILED, Map.of(...));
            throw ex;  // → 502 via GlobalExceptionHandler
        }

        // 6. Switch outcome
        return switch (outcome) {
            case CancelOutcome.Canceled c -> applyCanceledTransition(order, c, CancelSource.USER_REQUEST);
            case CancelOutcome.PendingCancel p -> applyPendingCancelTransition(order, p);
            case CancelOutcome.RaceFilled r -> applyRaceFilledTransition(order, r);
        };
    }
    ```
- ☐ **T2.12** `TradingService` — helpers privados:
    - `applyCanceledTransition(order, canceled, source)` — marca CANCELED + canceledAt + dispatch refund (BUY) o restore (SELL via D13 avgBuyPriceAtSubmission) + publish `OrderCanceledEvent`.
    - `applyPendingCancelTransition(order, pendingCancel)` — marca cancelRequestedAt + publish `OrderCancelPendingEvent`.
    - `applyRaceFilledTransition(order, raceFilled)` — D17 D-RACE-FILLED-UX: tratar como fill late-arrival. BUY: ajustar balance por delta `execution_total - quoted_total`. SELL: acreditar `execution_total`. Publish `OrderExecutedEvent` (no canceled).
    - `applyDriftReconcile(order)` — inline reuse de la lógica de Lote C (reconcile v2). Fetch estado real desde Alpaca → apply transition correspondiente.
    - `refundBuy(userId, quotedTotal)` — wrapper sobre `portfolioService.credit(userId, quotedTotal)`.
    - `restoreSellPosition(userId, ticker, qty, avgBuyPriceAtSubmission)` — wrapper sobre `portfolioService.upsertPosition` (re-INSERT si no existe).

### OrderController endpoint

- ☐ **T2.13** `trading/controller/OrderController.java` MODIFICADO — agregar endpoint:
    ```java
    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancelar orden Market en cola",
               description = "Cancela una orden con status=PENDING + alpacaOrderId. ...")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Orden cancelada (CANCELED) o cancel solicitado (status=PENDING + cancelRequestedAt) o race-filled (EXECUTED)"),
        @ApiResponse(responseCode = "404", description = "ORDER_NOT_FOUND"),
        @ApiResponse(responseCode = "409", description = "ORDER_NOT_CANCELABLE"),
        @ApiResponse(responseCode = "502", description = "BROKER_UNAVAILABLE")
    })
    public ResponseEntity<OrderResponse> cancelOrder(
        @PathVariable UUID id,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        OrderResponse response = tradingService.cancelOrder(principal.userId(), id);
        return ResponseEntity.ok(response);
    }
    ```

### Notification

- ☐ **T2.14** `notification/dto/OrderCanceledEmailCommand.java` NUEVO (record):
    ```java
    public record OrderCanceledEmailCommand(
        UUID userId,
        UUID orderId,
        OrderSide side,
        String ticker,
        int quantity,
        BigDecimal refundedAmount,  // BUY
        Integer restoredQty,         // SELL
        boolean isExpired,           // D15 — controla copy del template
        Instant canceledAtOrExpiredAt
    ) {}
    ```
- ☐ **T2.15** `notification/service/Notifier.java` MODIFICADO — agregar 2 métodos:
    ```java
    void notifyOrderCanceledBuy(OrderCanceledEmailCommand command);
    void notifyOrderCanceledSell(OrderCanceledEmailCommand command);
    ```
- ☐ **T2.16** `notification/service/MailNotifier.java` MODIFICADO — implementar los 2 métodos. Template `order-canceled-buy.html` o `order-canceled-sell.html` según el método. Context Thymeleaf incluye `isExpired` boolean.
- ☐ **T2.17** `backend/src/main/resources/templates/order-canceled-buy.html` NUEVO — inline-CSS estilo F09. Th:if `${isExpired}` para alternar entre "fue cancelada" y "expiró sin ejecutarse". Variables: `quantity`, `ticker`, `refundedAmount`, `newBalance` (opcional).
- ☐ **T2.18** `backend/src/main/resources/templates/order-canceled-sell.html` NUEVO — análogo. Variables: `quantity`, `ticker`, `restoredQty` (= quantity en este caso).

### OrderEventListener

- ☐ **T2.19** `trading/event/OrderEventListener.java` MODIFICADO — agregar 3 handlers:
    ```java
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void handleOrderCanceled(OrderCanceledEvent event) {
        OrderCanceledEmailCommand command = buildEmailCommand(event);
        switch (event.side()) {
            case BUY -> notifier.notifyOrderCanceledBuy(command);
            case SELL -> notifier.notifyOrderCanceledSell(command);
        }
        // Audit ORDER_CANCELED ya emitido en applyCanceledTransition (pre-commit dentro del tx)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOrderCancelPending(OrderCancelPendingEvent event) {
        // NO email — solo audit (ya emitido pre-commit como ORDER_CANCEL_REQUESTED con outcome=PENDING_CANCEL)
        log.info("Order cancel pending event processed, no email sent: orderId={}", event.orderId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOrderExpired(OrderExpiredEvent event) {
        OrderCanceledEmailCommand command = buildEmailCommandFromExpired(event);  // isExpired=true
        switch (event.side()) {
            case BUY -> notifier.notifyOrderCanceledBuy(command);
            case SELL -> notifier.notifyOrderCanceledSell(command);
        }
    }
    ```

### Tests unit del Lote B

- ☐ **T2.20** `TradingServiceCancelTest.java` NUEVO — con Mockito:
    - `cancelBuy_happyPath_pollingOK_refundsAndCancels`: setup BUY PENDING + alpacaOrderId. Mock adapter retorna `CancelOutcome.Canceled`. Assert: status=CANCELED, balance refunded, audit + event publicados.
    - `cancelSell_happyPath_pollingOK_restoresPositionViaReInsert`: setup SELL PENDING que liquidó posición. Mock adapter retorna Canceled. Assert: position re-INSERT con avg_buy_price del Order.
    - `cancelBuy_pollingTimeout_marksCancelRequestedAt`: Mock retorna `PendingCancel`. Assert: status sigue PENDING, cancelRequestedAt set, NO refund, NO email event.
    - `cancelBuy_raceFilled_treatedAsExecuted_balanceAdjusted`: Mock retorna `RaceFilled(filledAvgPrice=198.50, ...)` con execution_total < quoted_total. Assert: status=EXECUTED, balance ajustado por delta, `OrderExecutedEvent` publicado.
    - `cancel_orderNotFound_cross-user_throws404`: setup order del userB, principal=userA. Assert: `OrderNotFoundException`. NO se llama Alpaca.
    - `cancel_orderExecuted_throws409_NOT_CANCELABLE`: order con status=EXECUTED. Assert: `OrderNotCancelableException(currentStatus=EXECUTED)`.
    - `cancel_orderRejected_throws409`: idem REJECTED.
    - `cancel_orderFailed_throws409`: idem FAILED.
    - `cancel_orderExpired_throws409`: idem EXPIRED (caso edge — la orden ya transicionó por reconcile).
    - `cancel_idempotent_secondCallOnCanceled_returnsSameOrderNoSideEffects`: 1ra call procesa, 2da call no llama Alpaca, emit ORDER_DUPLICATE_CANCEL_REQUEST.
    - `cancel_idempotent_secondCallOnPendingWithCancelRequestedAt_returnsSameNoSecondDelete`.
    - `cancel_alpacaApiDown_throws502_noStateChange`: Mock `AlpacaApiException`. Assert: order intacta (sigue PENDING, NO cancelRequestedAt), audit `ORDER_CANCEL_FAILED`.
    - `cancel_alpacaReturns404_driftReconcileInline_appliesRealState`: Mock `AlpacaOrderNotFoundException`. Mock `getOrder` retorna real state `canceled`. Assert: order pasa a CANCELED + refund. Esto verifica el path drift inline.
- ☐ **T2.21** `OrderEventListenerCancelTest.java` NUEVO:
    - `handleOrderCanceledBuy_dispatchesEmailBuy_notSell`.
    - `handleOrderCanceledSell_dispatchesEmailSell_notBuy`.
    - `handleOrderCancelPending_doesNotCallNotifier`.
    - `handleOrderExpiredBuy_setsIsExpiredFlag_dispatchesEmailBuy`.

### Validación HITO 2

- ☐ **T2.22** `./mvnw test -Dtest='TradingServiceCancelTest,OrderEventListenerCancelTest'` — los ~16 tests verdes.
- ☐ **T2.23** `./mvnw test` — todos los tests unit del proyecto verdes (no regression). Esperar ~310+ unit tests pasando (294 base + 16 Lote B aprox).
- ☐ **T2.24** Manual sanity check: `curl -X POST http://localhost:8080/api/v1/orders/{some-uuid}/cancel -H "Authorization: Bearer <jwt>"` — verificar que el endpoint responde algo coherente (400/401 si JWT inválido, 404 si UUID no existe). NO testear flujo completo aún (eso es HITO 5).

**HITO 2 criterio:** `mvn test` (sin IT) verde. Endpoint cancela funcionalmente desde el punto de vista del service layer; aún no integrado con reconcile lazy v2.

---

## Lote C — Backend reconcile v2 + tests IT (HITO 3)

> Objetivo: extender `OrderReconciliationService` v1 a v2 para manejar transiciones outbound de Alpaca (canceled/rejected/expired). Tests IT cubren los flujos E2E con WireMock + PostgreSQL real.

### OrderReconciliationService v2

- ☐ **T3.1** `trading/service/OrderReconciliationService.java` MODIFICADO — método `reconcileOrder(Order order)` v2:
    - v1 actual maneja solo `PENDING → EXECUTED` (Alpaca filled). NO modificar ese path.
    - Agregar branches al switch del status retornado por Alpaca `GET /v2/orders/{id}`:
      - `canceled` → `applyCanceledTransition(order, alpacaSnapshot, CancelSource.BROKER_CANCEL or DRIFT_RECONCILE)`.
      - `expired` → `applyExpiredTransition(order, alpacaSnapshot)`.
      - `rejected` → `applyRejectedTransition(order, alpacaSnapshot)`.
      - `partially_filled` → `log.error("Unexpected partially_filled during reconcile, registering as debt"); skip` (D19 D-RECONCILE-LAZY-V2-SCOPE — no falla el GET caller).
- ☐ **T3.2** `OrderReconciliationService` — helpers privados (D22 D-RECONCILE-LAZY-V2-INLINE-EXTRACT):
    - `applyCanceledTransition(order, alpacaSnapshot, source)` — extraído como método package-private para uso desde `TradingService.applyDriftReconcile`. Marca status, refund/restore, publish event.
    - `applyExpiredTransition(order, alpacaSnapshot)` — análogo.
    - `applyRejectedTransition(order, alpacaSnapshot)` — análogo (reusa templates `order-rejected-*` heredados F09/F10 via Notifier).
- ☐ **T3.3** `TradingService.applyDriftReconcile(order)` (de T2.12) ahora invoca `orderReconciliationService.reconcileOrder(order)` directamente. Refactor confirma no-duplicación.

### Tests IT del Lote C

> Setup común: TestContainers con PostgreSQL real (perfil `application-test.yml`), WireMock standalone para Alpaca, MailHog stub o `@MockBean Notifier`.

- ☐ **T3.4** `OrderReconciliationServiceV2IT.java` NUEVO:
    - `reconcile_outboundCanceled_appliesRefundAndMarksCanceled`: seed BUY PENDING+alpacaOrderId con balance pre-debited. WireMock devuelve `status=canceled`. Trigger reconcile (via GET /portfolio). Assert: status=CANCELED, balance refunded, audit ORDER_CANCELED, MailHog 1 email.
    - `reconcile_outboundExpired_appliesRefundAndMarksExpired`: idem pero `status=expired`. Assert: status=EXPIRED, email con isExpired flag.
    - `reconcile_outboundRejected_appliesRefundAndMarksRejected`: idem `status=rejected`. Assert: status=REJECTED (existente F09), email rejected template.
    - `reconcile_outboundPartiallyFilled_logsAndSkips`: `status=partially_filled`. Assert: status sigue PENDING (no mutation), error log, NO email.
    - `reconcile_sellLegacyWithoutAvgBuyPriceAtSubmission_usesQuotedUnitPriceFallback`: orden SELL queued con `avg_buy_price_at_submission=NULL` (legacy pre-V6). Reconcile detecta `canceled`. Assert: re-INSERT position usa `quoted_unit_price` como fallback (R3 mitigation).

- ☐ **T3.5** `TradingControllerCancelIT.java` NUEVO:
    - `cancelBuy_happyPath_pollingOK_returns200WithCanceled`: setup orden BUY PENDING. WireMock DELETE 204 + GET fast canceled. POST /cancel. Assert: 200 con status=CANCELED + refundedAmount, balance ↑, posición intacta (no había en BUY), audit + email.
    - `cancelSell_happyPath_pollingOK_reInsertPosition`: setup SELL queued que liquidó posición. POST /cancel. Assert: 200 con status=CANCELED + restoredQty, position re-INSERT con avg_buy_price del Order.
    - `cancel_pollingTimeout_returns200WithPendingAndCancelRequestedAt`: WireMock GET siempre `accepted`. POST /cancel. Assert: 200 con status=PENDING + cancelRequestedAt poblado, balance intacto.
    - `cancel_pollingTimeoutThenReconcileMaterializes_endStateCanceled`: igual al anterior, después WireMock cambia a `canceled` y se hace GET /portfolio/positions. Assert tras GET: status=CANCELED, balance refunded, email tardío.
    - `cancel_raceFilled_returns200WithExecuted_notCanceled`: WireMock GET poll #3 devuelve `filled`. POST /cancel. Assert: 200 con status=EXECUTED, balance ajustado por execution_total real, audit ORDER_EXECUTED (no CANCELED).
    - `cancel_crossUser_returns404_noEnumeration`: order ord_X del userB; userA autenticado. POST /cancel ord_X. Assert: 404 ORDER_NOT_FOUND, body sin detalles, audit NO emite ORDER_CANCEL_REJECTED.
    - `cancel_orderExecuted_returns409`: order EXECUTED. Assert: 409 ORDER_NOT_CANCELABLE + details.currentStatus, audit ORDER_CANCEL_REJECTED.
    - `cancel_alpacaDown503Retries_returns502_orderIntact`: WireMock DELETE responde 503 × 3 retries. POST /cancel. Assert: 502 BROKER_UNAVAILABLE, order sigue PENDING sin cancelRequestedAt, audit ORDER_CANCEL_FAILED.
    - `cancel_alpaca404OnDelete_driftReconcileInline_returnsRealState`: WireMock DELETE responde 404; GET devuelve real state `canceled`. POST /cancel. Assert: 200 con status=CANCELED + refund (drift detected y resuelto inline).
    - `cancel_concurrencyTwoSimultaneous_oneProcessesOtherShortCircuit`: 2 POST /cancel paralelos (CompletableFuture × 2) sobre mismo order. Assert: 1 con `status=CANCELED + refundedAmount`, 1 con `status=CANCELED + alreadyCanceledAt`. WireMock recibe 1 solo DELETE.

### Validación HITO 3

- ☐ **T3.6** `./mvnw verify` (incluyendo IT) — todos los tests verdes. Baseline 363 actuales + 16 unit Lote B + 5 IT reconcile + 10 IT cancel ≈ ~394 tests aproximado (count final emerge).
- ☐ **T3.7** Verificación coverage: `./mvnw verify` + abrir `target/site/jacoco/index.html` — `TradingService.cancelOrder` ≥85%, `OrderReconciliationService` reconcile v2 ≥85%, `AlpacaTradingAdapter.cancelOrder` ≥80%. Si menor → registrar como D-COVERAGE-F15 en plan.md §2.4.
- ☐ **T3.8** Verificación que tests F09/F10/F16/F17/F18 actuales NO regresionan: revisar el output `mvn verify` y buscar tests fallidos en módulos previos. Si alguno cae → fix inmediato antes de Lote D.

**HITO 3 criterio:** `mvn verify` completo verde. Backend feature funcionalmente completo (sin frontend). Endpoint POST `/orders/{id}/cancel` funciona end-to-end con BD real.

---

## Lote D — Frontend (HITO 4)

> Objetivo: UI consume el endpoint nuevo + integra el botón "Cancelar" en `PendingOrdersPanel` y `RecentOrdersWidget`. Sin tests vitest por [[feedback-coverage-vs-velocidad]] — validación HITO 5 manual.

### Types + API

- ☐ **T4.1** `frontend/src/types/api.ts` MODIFICADO — extender:
    ```typescript
    export type OrderStatus = 'PENDING' | 'EXECUTED' | 'REJECTED' | 'FAILED' | 'CANCELED' | 'EXPIRED';

    export interface OrderResponse {
      // ... campos heredados F09/F10/F17 ...
      canceledAt?: string;          // ISO-8601
      cancelRequestedAt?: string;
      expiredAt?: string;
      refundedAmount?: string;      // BUY canceled/expired
      restoredQty?: number;         // SELL canceled/expired
    }
    ```
- ☐ **T4.2** `frontend/src/features/trading/api/ordersApi.ts` MODIFICADO — agregar:
    ```typescript
    export async function cancelOrder(orderId: string): Promise<OrderResponse> {
      const response = await axiosClient.post<OrderResponse>(`/api/v1/orders/${orderId}/cancel`);
      return response.data;
    }
    ```

### Hook useCancelOrder

- ☐ **T4.3** `frontend/src/features/trading/hooks/useCancelOrder.ts` NUEVO:
    ```typescript
    export function useCancelOrder() {
      const queryClient = useQueryClient();
      const { toast } = useToast();
      return useMutation<OrderResponse, ApiError, string>({
        mutationFn: (orderId) => cancelOrder(orderId),
        onSuccess: (response) => {
          // D21: invalidación granular
          queryClient.invalidateQueries({ queryKey: ['balance'] });
          queryClient.invalidateQueries({ queryKey: ['positions'] });
          queryClient.invalidateQueries({ queryKey: ['recentOrders'] });

          if (response.status === 'CANCELED') {
            toast.success(buildCancelSuccessMessage(response));  // "Orden cancelada — USD X restaurados / N acciones restauradas"
          } else if (response.cancelRequestedAt && response.status === 'PENDING') {
            toast.info(messages.ORDER_CANCEL_PENDING);  // "Cancelación en proceso..."
          } else if (response.status === 'EXECUTED') {
            toast.info(messages.ORDER_CANCEL_RACE_FILLED);
          }
        },
        onError: (error) => {
          if (error.code === 'ORDER_NOT_FOUND') toast.warning(messages.ORDER_NOT_FOUND);
          else if (error.code === 'ORDER_NOT_CANCELABLE') {
            toast.warning(messages.ORDER_NOT_CANCELABLE.replace('{currentStatus}', error.details?.currentStatus ?? '?'));
          }
          else if (error.code === 'BROKER_UNAVAILABLE') toast.error(messages.BROKER_UNAVAILABLE);
          else toast.error(messages.ORDER_CANCEL_ERROR_GENERIC);
        },
      });
    }
    ```
- ☐ **T4.4** Helper `buildCancelSuccessMessage(response)` que retorna string adaptado: BUY → "Orden cancelada — USD X restaurados a tu saldo"; SELL → "Orden cancelada — N acciones restauradas a tu posición".

### Component CancelOrderButton

- ☐ **T4.5** `frontend/src/features/trading/components/CancelOrderButton.tsx` NUEVO:
    ```tsx
    interface Props {
      order: OrderResponse;
    }

    export function CancelOrderButton({ order }: Props) {
      const { mutate, isPending } = useCancelOrder();
      const isCancelingFromTimeout = !!order.cancelRequestedAt && order.status === 'PENDING';

      if (isCancelingFromTimeout) {
        return (
          <div className="flex items-center gap-2 opacity-60" aria-busy="true">
            <Loader2 className="animate-spin h-4 w-4 text-gray-500" />
            <span className="text-sm text-gray-500">Cancelando…</span>
          </div>
        );
      }

      const confirmMessage = order.side === 'BUY'
        ? `¿Cancelar tu orden de compra de ${order.quantity} ${order.ticker}? Se restaurarán USD ${order.quotedTotal} a tu saldo.`
        : `¿Cancelar tu orden de venta de ${order.quantity} ${order.ticker}? Se restaurarán ${order.quantity} acciones a tu posición.`;

      return (
        <Button
          variant="ghost"
          size="sm"
          disabled={isPending}
          onClick={() => {
            if (window.confirm(confirmMessage)) mutate(order.id);
          }}
        >
          {isPending ? <Loader2 className="animate-spin h-4 w-4" /> : 'Cancelar'}
        </Button>
      );
    }
    ```

### Integraciones

- ☐ **T4.6** `frontend/src/features/portfolio/components/PendingOrdersPanel.tsx` MODIFICADO — agregar columna "Acciones" al final:
    - Header: `<th>Acciones</th>`.
    - Por fila: `<td><CancelOrderButton order={row} /></td>`.
- ☐ **T4.7** `frontend/src/features/dashboard/components/RecentOrdersWidget.tsx` MODIFICADO — columna condicional:
    - Header: `<th>Acciones</th>` (visible siempre — vacía en filas no-PENDING).
    - Por fila: `<td>{row.status === 'PENDING' && <CancelOrderButton order={row} />}</td>`.

### i18n

- ☐ **T4.8** `frontend/src/i18n/messages.es.ts` MODIFICADO — agregar:
    ```typescript
    ORDER_NOT_FOUND: "No se encontró la orden solicitada.",
    ORDER_NOT_CANCELABLE: "Tu orden ya está en estado {currentStatus} y no puede cancelarse.",
    BROKER_UNAVAILABLE: "El broker no respondió. Tu orden sigue en cola. Intenta de nuevo en unos minutos.",
    ORDER_CANCEL_SUCCESS_BUY: "Orden cancelada — USD {refundedAmount} restaurados a tu saldo",
    ORDER_CANCEL_SUCCESS_SELL: "Orden cancelada — {restoredQty} acciones restauradas a tu posición",
    ORDER_CANCEL_PENDING: "Cancelación en proceso. Verificaremos en unos segundos.",
    ORDER_CANCEL_RACE_FILLED: "Tu orden se ejecutó antes de que llegara la cancelación. La cancelación no fue aplicada.",
    ORDER_CANCEL_ERROR_GENERIC: "No pudimos procesar tu solicitud. Intenta nuevamente.",
    ```

### Validación HITO 4

- ☐ **T4.9** `cd frontend && npm run build` — build verde, ~3375 módulos (sin nuevos paquetes — todo de la lib existente).
- ☐ **T4.10** `npm test -- --run` — 27/27 vitest verdes (no regression).
- ☐ **T4.11** `npm run dev` — abrir http://localhost:5173, navegar `/portfolio` y `/dashboard`, verificar visualmente:
    - Si hay orden PENDING+alpacaOrderId: columna "Acciones" visible con botón "Cancelar".
    - Hover sobre el botón: cursor pointer + variant ghost se ve.
    - Si NO hay órdenes PENDING: panel vacío o sin filas. NO crash.

**HITO 4 criterio:** build + vitest verde + render manual exitoso. Backend + frontend integrados — listo para HITO 5 smoke E2E.

---

## Lote E — Tests IT cross-user adicionales + mvn verify final + smoke E2E (HITO 5)

> Objetivo: cerrar tests IT pendientes + ejecutar el demo E2E manual con el flujo completo BUY/SELL cancel + reconcile.

### Tests IT adicionales

- ☐ **T5.1** `TradingControllerCancelIT.java` MODIFICADO — agregar 2 IT pendientes:
    - `cancel_idempotency_pollingTimeoutThenSecondCancelNoSecondDelete`: 1er POST /cancel queda PENDING+cancelRequestedAt. 2do POST /cancel inmediatamente. Assert: 2da response es idéntica (mismo cancelRequestedAt), WireMock recibe SOLO 1 DELETE.
    - `cancel_raceConcurrentReconcileMaterializesExecuted_returns409`: setup orden PENDING+alpacaOrderId. Trigger reconcile lazy en GET /portfolio (WireMock devuelve `filled`) → status pasa a EXECUTED. Después POST /cancel sobre el mismo orderId. Assert: 409 ORDER_NOT_CANCELABLE con currentStatus=EXECUTED.

### mvn verify final

- ☐ **T5.2** `cd backend && ./mvnw clean verify` — pipeline completo verde.
- ☐ **T5.3** Anotar count final de tests: backend `mvn verify` (target ~395-405 tests), frontend `npm test -- --run` 27/27.
- ☐ **T5.4** `cd frontend && npm run build` final — verde.

### Smoke E2E manual (HITO 5)

> Requiere mercado abierto O setup WireMock standalone para simular escenarios. Si NYSE cerrado, usar mock manual.

- ☐ **T5.5** `docker compose up -d --build backend frontend` — rebuild con V6 + nuevo código.
- ☐ **T5.6** Login al sistema → `/portfolio`.
- ☐ **T5.7** **Setup orden PENDING+alpacaOrderId**: si hay una del demo F09 disponible, usar. Si no: colocar BUY Market en horario pre-mercado (NYSE 4-9:30am ET = 3-8:30am COL) — Alpaca devolverá `accepted`. Verificar `psql ... -c "SELECT id, status, alpaca_order_id FROM app.orders WHERE status='PENDING' AND alpaca_order_id IS NOT NULL ORDER BY submitted_at DESC LIMIT 1;"` retorna 1+ fila.
- ☐ **T5.8** **Smoke happy-path BUY cancel polling-OK**: en `/portfolio`, en la sección "Órdenes en cola" → click "Cancelar" en la fila → confirm dialog aparece con texto correcto (incluye monto a restaurar) → click "Aceptar" → toast verde "Orden cancelada — USD X restaurados" + fila desaparece del panel + `BalanceCard` actualiza con +USD X.
- ☐ **T5.9** **Verificación BD post-T5.8**: `psql ... -c "SELECT id, status, canceled_at, cancel_requested_at FROM app.orders WHERE id = '<orderId>';"` — assert status=CANCELED, canceled_at poblado, cancel_requested_at NULL.
- ☐ **T5.10** **Verificación email**: `localhost:8025` (MailHog UI) — verificar email "Tu orden de compra de N AAPL fue cancelada".
- ☐ **T5.11** **Verificación audit**: `localhost:5601` (Kibana) → buscar `event_type:ORDER_CANCEL_REQUESTED OR event_type:ORDER_CANCELED` filtrado por user → 2 entries con detalles correctos.
- ☐ **T5.12** **Verificación Alpaca dashboard**: `https://app.alpaca.markets/paper/dashboard/overview` → la orden aparece como `canceled`.
- ☐ **T5.13** **Smoke happy-path SELL cancel + re-INSERT** (si hay órden SELL queued disponible): repetir flujo. Verificar `psql ... -c "SELECT * FROM app.positions WHERE user_id=? AND ticker=?"` muestra fila con `quantity=<sellQty>` + `avg_buy_price` correcto.
- ☐ **T5.14** **Smoke polling-timeout** (simulado): para forzar polling-timeout, una opción es bloquear momentáneamente el host `paper-api.alpaca.markets` durante el GET polling (lento) o levantar WireMock standalone en `localhost:9000` reemplazando Alpaca trading via env var `ALPACA_BASE_URL=http://host.docker.internal:9000`. Si demasiado complejo → skip y marcar como deuda demo (no bloquea HITO 5).
- ☐ **T5.15** **Smoke negativo cross-user**: usar 2do usuario (de seed o crear manualmente) y POST /cancel a orderId del usuario 1 → 404 ORDER_NOT_FOUND.
- ☐ **T5.16** **Smoke negativo orden EXECUTED**: tras una orden ya ejecutada, POST /cancel → 409 ORDER_NOT_CANCELABLE.
- ☐ **T5.17** **Smoke desde RecentOrdersWidget** (`/dashboard`): si hay PENDING+alpacaOrderId, ver el botón "Cancelar" en la fila → click → mismo flujo. Verificar que invalidación granular refresca también la fila en este widget tras success.

**HITO 5 criterio:** los smokes happy-path BUY + SELL aceptados verbalmente por el usuario humano. Los smokes negativos opcionales pero recomendados. Polling-timeout puede diferirse a deuda si setup complejo.

---

## Lote F — Cierre (HITO 6)

> Objetivo: documentar emergentes, redactar APRENDIZAJES Día 11, actualizar AGENTS handoff, preparar commit message. Humano firma el commit + push + PR.

### Documentación emergente

- ☐ **T6.1** `specs/HU-F15-cancelar-orden/plan.md` MODIFICADO — completar §2.4 "Decisiones emergentes durante implementación (D25–Dxx)" con las decisiones reales que aparecieron durante los lotes B/C/D/E. Patrón estable F09 (7 D), F10 (5 D), F16+F21 (2 D), F18+F17 (3 D) — esperar ~3-7 emergentes en F15. Si la sección queda vacía: cuestionar si realmente se tomaron 0 decisiones nuevas o si están sin documentar.
- ☐ **T6.2** Si las decisiones emergentes afectan contratos API o flujos del SPEC: bump SPEC.md a v1.1 + changelog. Si solo son implementation-detail: SPEC se queda en v1.0.

### APRENDIZAJES.md sección "Día 11 — HU-F15"

- ☐ **T6.3** `APRENDIZAJES.md` MODIFICADO — agregar sección al inicio del archivo (o donde corresponda cronológicamente):
    ```markdown
    ## Día 11 — HU-F15 Cancelar orden Market

    ### Pattern reusable: polling canónico async para transiciones outbound
    [Reflexión sobre el polling 200ms × 10 como template para futuros cancels/edits/etc. ...]

    ### Reconcile lazy v2: extensión natural del v1 sin breaking change
    [Cómo el v1 hecho Día 10 se extendió aditivo a v2 sin reescribir. ...]

    ### Decisión contra-intuitiva: RACE_FILLED como sealed type, no excepción
    [D24 — modelar estados del broker como datos vs errores. ...]

    ### Idempotencia implícita por order.id vs explícita por clientOrderId
    [Cuando cada estrategia aplica. ...]

    ### Migración aditiva V6 vs reescritura del enum: trade-off de estados nuevos
    [Por qué CANCELED + EXPIRED sin IN_REVIEW/STOPPED, y cómo el chk_order_status se extendió sin DROP de filas. ...]

    ### Backfill SELL legacy sin avg_buy_price_at_submission
    [R3 mitigation con fallback a quoted_unit_price. ...]

    ### Meta-reflexión SDD: SPEC + plan + tasks como triada predictiva
    [Cómo los 3 docs juntos permitieron que las decisiones D-xx no requirieran "stop & ask" durante implementación. ...]
    ```
    Mínimo 5-7 reflexiones técnicas + 1-2 meta. Estilo Día 0/Día 1/Día 7/Día 8/Día 9/Día 10 del archivo.

### AGENTS.md handoff

- ☐ **T6.4** `AGENTS.md` MODIFICADO — actualizar sección "Trabajo activo":
    | Campo | Valor nuevo |
    |---|---|
    | Branch | `feat/HU-F15-cancelar-orden` cerrada. Próximo trabajo: **revamp UI con Claude Design** (sesión separada). |
    | HU activa | F15 cerrada. **NINGUNA HU pendiente del MVP académico.** Próxima sesión: revamp UI completo con `frontend-design` skill. |
    | Sprint | Sprint 2 funcional + MVP completo + bonus F17 + F15 ✅. Día 11 cerrado. |
    | Próximo paso | Revamp UI (sesión separada). Stretch: post-MVP HUs según prioridad. |
    | Deuda viva | Marcar #27 como CERRADA (CANCELED status). Marcar #30 como CERRADA (reconcile v2 outbound). Agregar emergentes Dxx si las hay. |

- ☐ **T6.5** `AGENTS.md` MODIFICADO — agregar nueva sección "Cómo continuar (post HU-F15 → revamp UI)" al inicio (después de la tabla "Trabajo activo"). Mover la sección actual "Cómo continuar (post Día 10 checkpoint 2 → HU-F15)" a "Histórico". Contenido nuevo:
    - Estado post-F15: tests acumulados, branches limpias, MVP completo.
    - Próxima sesión recomendada: `frontend-design` skill para revamp UI distintivo (alejarse del aesthetic genérico AI).
    - Áreas candidatas para revamp: `LoginPage`, `OrderForm`, `OrderQuotePanel`, `BalanceCard`, `PositionsTable`, `DashboardPage`, `RecentOrdersWidget`, theming general.
    - Decisiones a tomar al arrancar revamp: paleta, tipografía, layout grid, animaciones, mobile breakpoints.

### Commit message

- ☐ **T6.6** Crear `C:\Users\juang\AppData\Local\Temp\bt-hu-f15.txt` (ruta completa P6) con el mensaje del commit:
    ```
    feat(trading): cierra HU-F15 — cancelar orden Market

    Implementa el ciclo completo de cancelación de orden Market en estado PENDING+alpacaOrderId, con polling canónico async (2s timeout), reconcile lazy v2 para transiciones outbound del broker (canceled/rejected/expired), refund de balance (BUY) o restore de posición con re-INSERT (SELL), idempotencia implícita por order.id, audit + email transaccional. Frontend integra botón "Cancelar" en PendingOrdersPanel + RecentOrdersWidget con confirm dialog + visual feedback polling-timeout.

    Cambios principales:
    - V6: chk_order_status extendido a 6 valores (+ CANCELED, EXPIRED), 4 columnas nuevas en app.orders
    - AlpacaTradingAdapter.cancelOrder con polling DELETE + GET (200ms × 10)
    - CancelOutcome sealed type (Canceled / PendingCancel / RaceFilled)
    - TradingService.cancelOrder con dispatch BUY/SELL + lock canónico balances→positions
    - OrderReconciliationService v2: maneja canceled/rejected/expired desde Alpaca
    - 6 audit events nuevos + 2 templates email (reuso EXPIRED via flag)
    - Frontend: useCancelOrder hook + CancelOrderButton component
    - Tests: ~XX unit + ~YY IT nuevos (mvn verify ZZZ total)

    Cierra deudas: #27 (CANCELED status), #30 (reconcile v2 outbound)
    Decisiones documentadas: D1–DXX en specs/HU-F15-cancelar-orden/plan.md

    refs HU-F15
    specs/HU-F15-cancelar-orden/SPEC.md

    Co-authored-by: Claude <noreply@anthropic.com>
    ```
    Reemplazar `XX/YY/ZZZ` y `DXX` con los valores reales tras T5.3 + T6.1.

### Verificación pre-commit

- ☐ **T6.7** `cd backend && ./mvnw verify` — última pasada verde.
- ☐ **T6.8** `cd frontend && npm run build && npm test -- --run` — última pasada verde.
- ☐ **T6.9** `git status` — verificar todos los archivos modificados/nuevos esperados: backend (~25 archivos), frontend (~6 archivos), specs/HU-F15-cancelar-orden/ (3 archivos), AGENTS.md, APRENDIZAJES.md.
- ☐ **T6.10** `git diff --stat origin/main..HEAD` — confirmar diff razonable (no incluir archivos `.swp`, `node_modules`, etc.).
- ☐ **T6.11** Mensaje del Lote F al humano: "F15 cerrada. Listo para tu firma. `git add -A && git commit -F C:\Users\juang\AppData\Local\Temp\bt-hu-f15.txt && git push -u origin feat/HU-F15-cancelar-orden && gh pr create ...`".

### Validación HITO 6

- ☐ **T6.12** Humano firma el commit, push, abre PR, mergea con squash a `main`. Branch borrada en remoto.
- ☐ **T6.13** Verificación post-merge: `git checkout main && git pull` muestra el commit squash. `mvn verify` en main verde.

**HITO 6 criterio:** PR mergeada a main. MVP completo + F15 cerrado. Próxima sesión = revamp UI (separada).

---

## Resumen ejecutivo

| Lote | HITO | Tareas | Tests añadidos | Esfuerzo |
|---|---|---|---|---|
| A | Compile + V6 + unit Lote A | T1.1–T1.25 (25) | ~13 unit (adapter + Order entity) | ~2-3h |
| B | mvn test verde | T2.1–T2.24 (24) | ~16 unit (service + listener) | ~3-4h |
| C | mvn verify verde | T3.1–T3.8 (8) | ~15 IT (reconcile v2 + controller) | ~3-4h |
| D | npm build verde | T4.1–T4.11 (11) | 0 (skip vitest) | ~1.5-2h |
| E | smoke E2E aceptado | T5.1–T5.17 (17) | +2 IT (idempotency + race reconcile) | ~1-2h |
| F | PR mergeada | T6.1–T6.13 (13) | — | ~0.5-1h |
| **Total** | — | **98 tareas** | **~46 tests nuevos** | **~11-15h** |

**Cadencia validación:** después de cada HITO (1–6), pausar para validación humana antes de continuar al siguiente lote. NO micro-checkpoint tras cada T{lote}.{n} [[feedback-cadencia-sdd]].
