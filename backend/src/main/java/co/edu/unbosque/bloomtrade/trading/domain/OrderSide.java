package co.edu.unbosque.bloomtrade.trading.domain;

/**
 * Lado de una orden de trading: compra o venta.
 *
 * <p>HU-F09 implementó {@code BUY}. HU-F10 (Día 7) habilita {@code SELL} reutilizando el
 * mismo endpoint {@code POST /api/v1/orders} con dispatch interno en
 * {@code TradingService.placeOrderTx} (D5 D-TRADING-METHOD). El código
 * {@code SIDE_NOT_YET_IMPLEMENTED} se mantiene en {@code validation-messages.properties}
 * para retro-compatibilidad pero deja de emitirse desde el backend (SPEC F10 §5.3.3).
 */
public enum OrderSide {
    BUY,
    SELL
}
