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
     * Transiciona a {@code EXECUTED} tras confirmación exitosa de Alpaca.
     * {@code executionTotal} se calcula como {@code executionUnitPrice × quantity + quotedCommission}
     * (la comisión se honra del quote, no se recalcula sobre el precio de ejecución — decisión
     * D4 sin slippage: el quote es la base contractual).
     */
    public void markAsExecuted(String alpacaOrderId, BigDecimal executionUnitPrice) {
        if (this.status != OrderStatus.PENDING) {
            throw new IllegalStateException(
                    "No se puede marcar como EXECUTED desde status " + this.status);
        }
        this.status = OrderStatus.EXECUTED;
        this.alpacaOrderId = alpacaOrderId;
        this.executionUnitPrice = executionUnitPrice;
        this.executionTotal = executionUnitPrice
                .multiply(BigDecimal.valueOf(this.quantity))
                .add(this.quotedCommission);
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
}
