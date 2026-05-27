package co.edu.unbosque.bloomtrade.integration.alpaca;

/**
 * Alpaca devolvió 422 al intentar cancelar — la orden no es cancelable según su lado
 * (HU-F15). Caso típico: ya filled, ya canceled previamente, o en estado terminal en Alpaca
 * pero BD local todavía la tiene como PENDING.
 *
 * <p>{@code TradingService.cancelOrder} la trata igual que {@link AlpacaOrderNotFoundException}:
 * dispara reconcile lazy v2 inline (drift detected) para sincronizar BD con el estado real
 * de Alpaca antes de responder al cliente.
 */
public class AlpacaOrderNotCancelableException extends AlpacaApiException {

    private final String alpacaOrderId;

    public AlpacaOrderNotCancelableException(String alpacaOrderId) {
        super("Alpaca rechazó cancel: orden ya en estado terminal — " + alpacaOrderId, 1);
        this.alpacaOrderId = alpacaOrderId;
    }

    public String getAlpacaOrderId() {
        return alpacaOrderId;
    }
}
