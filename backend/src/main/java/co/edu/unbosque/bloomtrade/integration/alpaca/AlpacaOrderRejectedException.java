package co.edu.unbosque.bloomtrade.integration.alpaca;

/**
 * Alpaca rechazó la orden con razón explícita (HU-F09 Lote B). Casos típicos:
 * símbolo no soportado por Alpaca paper, qty inválida según sus reglas, mercado cerrado
 * en su lado, buying_power insuficiente del paper account global.
 *
 * <p>NO es un error técnico — Alpaca respondió, dijo "no". El {@code @Retry} la lista en
 * {@code ignore-exceptions}: reintentarla no cambiaría nada. El {@code GlobalExceptionHandler}
 * mapea esta a HTTP 422 {@code ALPACA_ORDER_REJECTED} con el {@link #alpacaReason} en details.
 */
public class AlpacaOrderRejectedException extends RuntimeException {

    private final String alpacaReason;
    private final String alpacaOrderId;

    public AlpacaOrderRejectedException(String alpacaReason, String alpacaOrderId) {
        super("Alpaca rechazó la orden: " + alpacaReason);
        this.alpacaReason = alpacaReason;
        this.alpacaOrderId = alpacaOrderId;
    }

    public String getAlpacaReason() {
        return alpacaReason;
    }

    public String getAlpacaOrderId() {
        return alpacaOrderId;
    }
}
