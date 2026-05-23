package co.edu.unbosque.bloomtrade.trading.service;

import co.edu.unbosque.bloomtrade.trading.domain.Order;
import co.edu.unbosque.bloomtrade.trading.domain.OrderSide;
import java.util.UUID;

/**
 * Input limpio del {@code TradingService} al {@code AlpacaTradingAdapter}. Aísla al adapter
 * del agregado {@link Order} — el adapter no debe conocer la estructura JPA, solo los datos
 * mínimos para construir el request a Alpaca (HU-F09 D20).
 */
public record SubmitMarketOrderCommand(
        UUID clientOrderId, String ticker, int quantity, OrderSide side) {

    /** Extrae los datos relevantes de una {@link Order} en estado {@code PENDING}. */
    public static SubmitMarketOrderCommand from(Order order) {
        return new SubmitMarketOrderCommand(
                order.getClientOrderId(), order.getTicker(), order.getQuantity(), order.getSide());
    }
}
