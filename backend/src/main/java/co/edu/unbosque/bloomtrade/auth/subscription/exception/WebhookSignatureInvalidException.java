package co.edu.unbosque.bloomtrade.auth.subscription.exception;

/**
 * El header {@code Stripe-Signature} no coincide con el HMAC esperado o el body fue alterado
 * (spec §5.3.4). Mapea a 400 {@code WEBHOOK_SIGNATURE_INVALID}. Stripe NO reintenta sobre 400.
 */
public class WebhookSignatureInvalidException extends RuntimeException {

    public WebhookSignatureInvalidException(Throwable cause) {
        super("Stripe-Signature inválida", cause);
    }
}
