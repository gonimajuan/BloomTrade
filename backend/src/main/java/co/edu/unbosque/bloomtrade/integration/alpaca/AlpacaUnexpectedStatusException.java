package co.edu.unbosque.bloomtrade.integration.alpaca;

/**
 * Alpaca devolvió un {@code status} no contemplado durante el polling de cancelación o el
 * reconcile lazy v2 (HU-F15 D19 D-RECONCILE-LAZY-V2-SCOPE). Casos típicos:
 * <ul>
 *   <li>{@code partially_filled} — Market Orders raramente parcial-fillan en paper, pero si
 *       emerge se trata como error inesperado (fuera de scope F15; deuda registrada).</li>
 *   <li>Cualquier otro status no esperado ({@code suspended}, etc.).</li>
 * </ul>
 *
 * <p>NO confundir con {@link AlpacaOrderRejectedException} (rechazo lógico esperado en submit)
 * ni {@link AlpacaApiException} (error técnico de red/5xx). Esta es señal de un wire format
 * inesperado.
 */
public class AlpacaUnexpectedStatusException extends AlpacaApiException {

    private final String alpacaOrderId;
    private final String unexpectedStatus;

    public AlpacaUnexpectedStatusException(String alpacaOrderId, String unexpectedStatus) {
        super(
                "Alpaca devolvió status inesperado durante operación: "
                        + unexpectedStatus
                        + " (orderId="
                        + alpacaOrderId
                        + ")",
                1);
        this.alpacaOrderId = alpacaOrderId;
        this.unexpectedStatus = unexpectedStatus;
    }

    public String getAlpacaOrderId() {
        return alpacaOrderId;
    }

    public String getUnexpectedStatus() {
        return unexpectedStatus;
    }
}
