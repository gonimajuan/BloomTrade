package co.edu.unbosque.bloomtrade.integration.alpaca;

/**
 * Alpaca devolvió 404 al intentar cancelar — el {@code alpacaOrderId} ya no existe en su lado
 * (HU-F15). Caso típico: drift entre BD local y Alpaca, ej. Alpaca canceló por TIF day expirado
 * o filled fuera de banda.
 *
 * <p>NO es un error técnico — Alpaca respondió coherentemente. {@code TradingService.cancelOrder}
 * captura esta excepción y dispara reconcile lazy v2 inline (drift detected) para sincronizar el
 * estado real con BD antes de responder al cliente.
 */
public class AlpacaOrderNotFoundException extends AlpacaApiException {

    private final String alpacaOrderId;

    public AlpacaOrderNotFoundException(String alpacaOrderId) {
        super("Alpaca no encontró la orden: " + alpacaOrderId + " (drift detectado)", 1);
        this.alpacaOrderId = alpacaOrderId;
    }

    public String getAlpacaOrderId() {
        return alpacaOrderId;
    }
}
