package co.edu.unbosque.bloomtrade.integration.alpaca.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * Respuesta de Alpaca {@code POST /v2/orders} y {@code GET /v2/orders/{id}} (HU-F09 Lote B).
 *
 * <p>Alpaca expone muchos más campos (asset_class, order_class, extended_hours, etc.); solo
 * mapeamos los relevantes para HU-F09. {@code @JsonIgnoreProperties(ignoreUnknown = true)}
 * tolera campos nuevos sin romper.
 *
 * <p>Status posibles relevantes:
 * <ul>
 *   <li>{@code accepted} — Alpaca aceptó la orden, pendiente de match</li>
 *   <li>{@code filled} — ejecutada totalmente (paper trading: típicamente &lt;500ms)</li>
 *   <li>{@code partially_filled} — fill parcial (no aplica a market orders en paper)</li>
 *   <li>{@code rejected} — Alpaca rechazó (qty inválida, símbolo no soportado, etc.)</li>
 *   <li>{@code canceled} — cancelada (HU-F15 stretch)</li>
 * </ul>
 *
 * <p>{@code filled_avg_price} y {@code filled_qty} son string en el wire format; se parsean
 * a {@link BigDecimal} para mantener precisión (D12). Null cuando aún no se ha llenado.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlpacaOrderResponse(
        String id,
        @JsonProperty("client_order_id") String clientOrderId,
        String symbol,
        String qty,
        String side,
        String type,
        String status,
        @JsonProperty("filled_avg_price") BigDecimal filledAvgPrice,
        @JsonProperty("filled_qty") String filledQty,
        @JsonProperty("rejected_reason") String rejectedReason,
        @JsonProperty("submitted_at") String submittedAt,
        @JsonProperty("filled_at") String filledAt) {

    public boolean isFilled() {
        return "filled".equalsIgnoreCase(status);
    }

    public boolean isRejected() {
        return "rejected".equalsIgnoreCase(status);
    }

    /** Estado terminal: ya no transicionará más (filled o rejected o canceled). */
    public boolean isTerminal() {
        return isFilled() || isRejected() || "canceled".equalsIgnoreCase(status);
    }
}
