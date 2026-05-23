package co.edu.unbosque.bloomtrade.portfolio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Posición del inversionista en un ticker (tabla {@code app.positions}, migración V5).
 *
 * <p>Invariante: una sola fila por {@code (user_id, ticker)} (UNIQUE constraint BD).
 * Las compras se acumulan vía {@link #incrementBy} con recálculo de
 * {@code avg_buy_price} como promedio ponderado. Las ventas (HU-F10) la decrementarán.
 *
 * <p>HU-F09 D12: precios en {@code NUMERIC(19,4)} con {@link RoundingMode#HALF_UP}.
 */
@Entity
@Table(schema = "app", name = "positions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Position {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "ticker", nullable = false, length = 10, updatable = false)
    private String ticker;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "avg_buy_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal avgBuyPrice;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private Position(UUID userId, String ticker, Integer quantity, BigDecimal avgBuyPrice) {
        this.userId = userId;
        this.ticker = ticker;
        this.quantity = quantity;
        this.avgBuyPrice = avgBuyPrice;
    }

    /** Crea una posición nueva (primera compra del ticker para este usuario). */
    public static Position newPosition(
            UUID userId, String ticker, Integer quantity, BigDecimal avgBuyPrice) {
        return new Position(userId, ticker, quantity, avgBuyPrice);
    }

    /**
     * Incrementa la posición con una compra adicional. Recalcula
     * {@code avg_buy_price_new = ((qty × avg_buy_price) + (additionalQty × additionalUnitPrice)) / qty_new}
     * con scale=4 y HALF_UP. Setea {@code updatedAt} automáticamente via {@link UpdateTimestamp}.
     */
    public void incrementBy(int additionalQty, BigDecimal additionalUnitPrice) {
        if (additionalQty <= 0) {
            throw new IllegalArgumentException("additionalQty debe ser > 0, recibido: " + additionalQty);
        }
        if (additionalUnitPrice == null || additionalUnitPrice.signum() <= 0) {
            throw new IllegalArgumentException("additionalUnitPrice debe ser > 0");
        }
        BigDecimal currentValue = this.avgBuyPrice.multiply(BigDecimal.valueOf(this.quantity));
        BigDecimal additionalValue = additionalUnitPrice.multiply(BigDecimal.valueOf(additionalQty));
        int newQuantity = this.quantity + additionalQty;
        BigDecimal newAvgBuyPrice = currentValue
                .add(additionalValue)
                .divide(BigDecimal.valueOf(newQuantity), 4, RoundingMode.HALF_UP);
        this.quantity = newQuantity;
        this.avgBuyPrice = newAvgBuyPrice;
    }
}
