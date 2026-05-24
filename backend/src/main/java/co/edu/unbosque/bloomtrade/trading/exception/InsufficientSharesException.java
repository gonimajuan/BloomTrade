package co.edu.unbosque.bloomtrade.trading.exception;

/**
 * El usuario tiene posición en el ticker pero la cantidad disponible es menor que la
 * solicitada para vender (HU-F10 SPEC §5.3.5).
 *
 * <p>El {@code GlobalExceptionHandler} mapea esta excepción a HTTP 409
 * {@code INSUFFICIENT_SHARES} con details {@code { available, requested, ticker }}.
 *
 * <p>Análoga arquitectónica a {@code InsufficientFundsException} (HU-F09): defensa de
 * invariante "no vender más de lo que se tiene" antes de tocar Alpaca.
 */
public class InsufficientSharesException extends RuntimeException {

    private final int available;
    private final int requested;
    private final String ticker;

    public InsufficientSharesException(int available, int requested, String ticker) {
        super(
                "Solo tienes "
                        + available
                        + " "
                        + ticker
                        + " disponibles para vender (solicitaste "
                        + requested
                        + ")");
        this.available = available;
        this.requested = requested;
        this.ticker = ticker;
    }

    public int getAvailable() {
        return available;
    }

    public int getRequested() {
        return requested;
    }

    public String getTicker() {
        return ticker;
    }
}
