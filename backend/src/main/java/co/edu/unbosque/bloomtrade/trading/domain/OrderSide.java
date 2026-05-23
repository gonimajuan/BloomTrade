package co.edu.unbosque.bloomtrade.trading.domain;

/**
 * Lado de una orden de trading: compra o venta.
 *
 * <p>HU-F09 implementa solo {@code BUY}. {@code SELL} se incluye porque la API REST
 * lo acepta (mismo endpoint {@code POST /api/v1/orders}) pero el handler lanza
 * {@code SIDE_NOT_YET_IMPLEMENTED} hasta HU-F10 (Día 7).
 */
public enum OrderSide {
    BUY,
    SELL
}
