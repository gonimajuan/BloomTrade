package co.edu.unbosque.bloomtrade.trading.exception;

/**
 * Intento de venta sobre un ticker sin posición (HU-F10 SPEC §5.3.4). BloomTrade es
 * long-only por diseño: si no existe fila en {@code app.positions} para {@code (userId, ticker)}
 * o la fila tiene {@code quantity = 0}, la venta se rechaza ANTES de llamar a Alpaca.
 *
 * <p>El {@code GlobalExceptionHandler} mapea esta excepción a HTTP 409
 * {@code SHORT_SELLING_NOT_ALLOWED} con details {@code { ticker, requestedQty }}.
 */
public class ShortSellingNotAllowedException extends RuntimeException {

    private final String ticker;
    private final int requestedQty;

    public ShortSellingNotAllowedException(String ticker, int requestedQty) {
        super(
                "No tienes posición en "
                        + ticker
                        + ". BloomTrade no permite ventas en corto (requestedQty="
                        + requestedQty
                        + ")");
        this.ticker = ticker;
        this.requestedQty = requestedQty;
    }

    public String getTicker() {
        return ticker;
    }

    public int getRequestedQty() {
        return requestedQty;
    }
}
