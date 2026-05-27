package co.edu.unbosque.bloomtrade.trading.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Orden de trading (tabla {@code app.orders}, migración V5).
 *
 * <p>Se construye únicamente vía {@link #newPending} con los datos del quote ya validados
 * (precio, comisión, total). Status inicial siempre {@code PENDING}. Transiciones a estado
 * terminal vía {@link #markAsExecuted}, {@link #markAsRejected} o {@link #markAsFailed} —
 * no se permite mutar status directamente desde fuera (encapsulación TAC-M3).
 *
 * <p>HU-F09 D12: precios y montos en {@link BigDecimal} con {@code NUMERIC(19,4)} en BD.
 * La aritmética financiera se hace con {@code RoundingMode.HALF_UP} (ver
 * {@code OrderOrchestrator}, no acá — esta entidad solo persiste valores ya calculados).
 *
 * <p>HU-F09 D16: el enum {@link OrderStatus} restringe los estados a 4 valores. CHECK
 * constraint a nivel BD también lo enforce.
 */
@Entity
@Table(schema = "app", name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "client_order_id", nullable = false, updatable = false)
    private UUID clientOrderId;

    @Column(name = "ticker", nullable = false, length = 10, updatable = false)
    private String ticker;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 4, updatable = false)
    private OrderSide side;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10, updatable = false)
    private OrderType type;

    @Column(name = "quantity", nullable = false, updatable = false)
    private Integer quantity;

    @Column(name = "quoted_unit_price", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal quotedUnitPrice;

    @Column(name = "quoted_commission", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal quotedCommission;

    @Column(name = "quoted_total", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal quotedTotal;

    @Column(name = "execution_unit_price", precision = 19, scale = 4)
    private BigDecimal executionUnitPrice;

    @Column(name = "execution_total", precision = 19, scale = 4)
    private BigDecimal executionTotal;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private OrderStatus status;

    @Column(name = "alpaca_order_id", length = 80)
    private String alpacaOrderId;

    @Column(name = "error_code", length = 40)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    @Column(name = "executed_at")
    private Instant executedAt;

    // ─── HU-F15 — Cancelación de órdenes (V6) ──────────────────────────────────

    /**
     * Cancel solicitado pero polling Alpaca dio timeout — la orden queda PENDING esperando que
     * {@code OrderReconciliationService} v2 materialice la transición a CANCELED (SPEC §5.2.1).
     * Coexiste con {@code status=PENDING}.
     */
    @Column(name = "cancel_requested_at")
    private Instant cancelRequestedAt;

    /** Timestamp local de la transición a {@code CANCELED}. NULL si {@code status != CANCELED}. */
    @Column(name = "canceled_at")
    private Instant canceledAt;

    /**
     * Timestamp local de la transición a {@code EXPIRED} (TIF day expirado sin fill, detectado
     * vía reconcile lazy v2). NULL si {@code status != EXPIRED}.
     */
    @Column(name = "expired_at")
    private Instant expiredAt;

    /**
     * D13 D-RESTORE-AVG-BUY-PRICE: snapshot del {@code avg_buy_price} de la posición al
     * momento de SELL, capturado por {@code TradingService} pre-INSERT. Si la SELL queued se
     * cancela y la posición fue liquidada por el decrement optimista (D-SELL-QUEUED-RISK F10),
     * el re-INSERT a {@code app.positions} usa este valor para preservar el costo histórico.
     * NULL para BUY (no aplica).
     */
    @Column(name = "avg_buy_price_at_submission", precision = 19, scale = 4)
    private BigDecimal avgBuyPriceAtSubmission;

    private Order(
            UUID userId,
            UUID clientOrderId,
            String ticker,
            OrderSide side,
            OrderType type,
            Integer quantity,
            BigDecimal quotedUnitPrice,
            BigDecimal quotedCommission,
            BigDecimal quotedTotal) {
        this.userId = userId;
        this.clientOrderId = clientOrderId;
        this.ticker = ticker;
        this.side = side;
        this.type = type;
        this.quantity = quantity;
        this.quotedUnitPrice = quotedUnitPrice;
        this.quotedCommission = quotedCommission;
        this.quotedTotal = quotedTotal;
        this.status = OrderStatus.PENDING;
    }

    /**
     * Crea una orden con status {@code PENDING}. Los valores del quote (precio, comisión, total)
     * ya deben venir calculados con {@code BigDecimal} HALF_UP (responsabilidad del caller —
     * típicamente {@code TradingService#placeOrder}).
     */
    public static Order newPending(
            UUID userId,
            UUID clientOrderId,
            String ticker,
            OrderSide side,
            OrderType type,
            Integer quantity,
            BigDecimal quotedUnitPrice,
            BigDecimal quotedCommission,
            BigDecimal quotedTotal) {
        return new Order(
                userId,
                clientOrderId,
                ticker,
                side,
                type,
                quantity,
                quotedUnitPrice,
                quotedCommission,
                quotedTotal);
    }

    /**
     * Transiciona a {@code EXECUTED} tras confirmación exitosa de Alpaca. Side-aware:
     * <ul>
     *   <li>{@code BUY}: {@code executionTotal = executionUnitPrice × quantity + quotedCommission}
     *       (lo COBRADO al usuario).</li>
     *   <li>{@code SELL}: {@code executionTotal = executionUnitPrice × quantity − quotedCommission}
     *       (lo ACREDITADO al usuario — producto neto).</li>
     * </ul>
     * La comisión se honra del quote, no se recalcula sobre el precio de ejecución (decisión D4
     * F09: el quote es la base contractual, sin tolerancia configurable a slippage en MVP).
     */
    public void markAsExecuted(String alpacaOrderId, BigDecimal executionUnitPrice) {
        if (this.status != OrderStatus.PENDING) {
            throw new IllegalStateException(
                    "No se puede marcar como EXECUTED desde status " + this.status);
        }
        this.status = OrderStatus.EXECUTED;
        this.alpacaOrderId = alpacaOrderId;
        this.executionUnitPrice = executionUnitPrice;
        BigDecimal subtotal = executionUnitPrice.multiply(BigDecimal.valueOf(this.quantity));
        this.executionTotal =
                (this.side == OrderSide.BUY)
                        ? subtotal.add(this.quotedCommission)
                        : subtotal.subtract(this.quotedCommission);
        this.executedAt = Instant.now();
    }

    /**
     * Vincula la orden con el {@code alpacaOrderId} sin transicionar de estado (D29 emergente
     * Lote H.5). Útil cuando Alpaca aceptó la orden pero el mercado está cerrado: la orden queda
     * en {@code PENDING + alpacaOrderId != null} (= "encolada en Alpaca, esperando fill cuando
     * abra"). El fill final se resolverá vía reconciliation job o webhook (deuda registrada).
     */
    public void linkToAlpaca(String alpacaOrderId) {
        if (this.status != OrderStatus.PENDING) {
            throw new IllegalStateException(
                    "No se puede vincular Alpaca order ID desde status " + this.status);
        }
        this.alpacaOrderId = alpacaOrderId;
    }

    /**
     * Transiciona a {@code REJECTED} (Alpaca rechazó explícitamente o fondos insuficientes
     * detectados en la ejecución por race condition).
     */
    public void markAsRejected(String errorCode, String errorMessage) {
        if (this.status != OrderStatus.PENDING) {
            throw new IllegalStateException(
                    "No se puede marcar como REJECTED desde status " + this.status);
        }
        this.status = OrderStatus.REJECTED;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    /**
     * Transiciona a {@code FAILED} (Alpaca caída tras 3 retries, market data caído, etc. —
     * error técnico, no rechazo de mercado).
     */
    public void markAsFailed(String errorCode, String errorMessage) {
        if (this.status != OrderStatus.PENDING) {
            throw new IllegalStateException(
                    "No se puede marcar como FAILED desde status " + this.status);
        }
        this.status = OrderStatus.FAILED;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    // ─── HU-F15 — Cancelación ────────────────────────────────────────────────

    /**
     * Marca {@code cancel_requested_at} sin cambiar status. Usado en el path polling-timeout
     * (SPEC §5.2.1 D6): la cancelación fue solicitada y Alpaca aceptó el DELETE, pero el polling
     * agotó el timeout antes de ver {@code status=canceled}. Reconcile lazy v2 materializará
     * la transición final.
     */
    public void markCancelRequested() {
        if (this.status != OrderStatus.PENDING) {
            throw new IllegalStateException(
                    "No se puede marcar cancel solicitado desde status " + this.status);
        }
        if (this.cancelRequestedAt != null) {
            // Idempotencia §5.2.4: re-request preserva el timestamp original.
            return;
        }
        this.cancelRequestedAt = Instant.now();
    }

    /**
     * Transiciona a {@code CANCELED}. Invocado tanto en path polling-OK ({@code TradingService})
     * como por reconcile lazy v2 ({@code OrderReconciliationService}) cuando Alpaca confirma
     * la cancelación outbound. El refund/restore lo hace el caller, no esta entidad.
     */
    public void markAsCanceled() {
        if (this.status != OrderStatus.PENDING) {
            throw new IllegalStateException(
                    "No se puede marcar como CANCELED desde status " + this.status);
        }
        this.status = OrderStatus.CANCELED;
        this.canceledAt = Instant.now();
    }

    /**
     * Transiciona a {@code EXPIRED} (TIF day expirado en Alpaca, detectado vía reconcile lazy
     * v2). El reverse de balance/position se aplica igual que en CANCELED — desde el caller.
     */
    public void markAsExpired() {
        if (this.status != OrderStatus.PENDING) {
            throw new IllegalStateException(
                    "No se puede marcar como EXPIRED desde status " + this.status);
        }
        this.status = OrderStatus.EXPIRED;
        this.expiredAt = Instant.now();
    }

    /**
     * D13 D-RESTORE-AVG-BUY-PRICE: setter package-private invocado por {@code TradingService}
     * en el path SELL pre-INSERT con el {@code avg_buy_price} actual de la posición. Necesario
     * para preservar el costo histórico si la SELL se cancela tras haber liquidado la posición.
     */
    public void linkAvgBuyPriceAtSubmission(BigDecimal avgBuyPrice) {
        if (this.side != OrderSide.SELL) {
            throw new IllegalStateException(
                    "avg_buy_price_at_submission solo aplica a SELL; este order es " + this.side);
        }
        this.avgBuyPriceAtSubmission = avgBuyPrice;
    }

    /**
     * Verdadero si la orden está en estado cancelable según SPEC §5.3.2: {@code PENDING} con
     * {@code alpaca_order_id} ya asignado (= Alpaca confirmó recepción inicial). Defensa
     * primaria del flujo de cancelación.
     */
    public boolean isCancelable() {
        return this.status == OrderStatus.PENDING && this.alpacaOrderId != null;
    }
}
