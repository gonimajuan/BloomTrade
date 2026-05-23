package co.edu.unbosque.bloomtrade.integration.alpaca.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * Cuerpo del request {@code POST /v2/orders} de Alpaca (HU-F09 Lote B).
 *
 * <p>Campos snake_case requeridos por Alpaca; Jackson los serializa con {@link JsonProperty}.
 * El {@code client_order_id} (D14) Alpaca lo respeta como key de deduplicación nativa.
 *
 * <p>Para market orders en paper trading: {@code type="market"} + {@code time_in_force="day"}.
 * {@code qty} se envía como string (Alpaca lo acepta así).
 */
public record AlpacaOrderRequest(
        String symbol,
        String qty,
        String side,
        String type,
        @JsonProperty("time_in_force") String timeInForce,
        @JsonProperty("client_order_id") UUID clientOrderId) {

    /** Factory para una market order BUY/SELL con {@code time_in_force=day}. */
    public static AlpacaOrderRequest market(
            String symbol, int quantity, String side, UUID clientOrderId) {
        return new AlpacaOrderRequest(
                symbol, Integer.toString(quantity), side.toLowerCase(), "market", "day", clientOrderId);
    }
}
