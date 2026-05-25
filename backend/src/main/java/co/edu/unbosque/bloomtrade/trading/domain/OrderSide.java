package co.edu.unbosque.bloomtrade.trading.domain;

/**
 * Lado de una orden de trading: compra o venta.
 *
 * <p>HU-F09 implementó {@code BUY}. HU-F10 (PR #7) habilitó {@code SELL} reutilizando el
 * mismo endpoint {@code POST /api/v1/orders} con dispatch interno en
 * {@code TradingService.placeOrderTx} (D5 D-TRADING-METHOD).
 */
public enum OrderSide {
    BUY,
    SELL
}
