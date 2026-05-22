package co.edu.unbosque.bloomtrade.auth.subscription.exception;

/**
 * Error técnico al llamar al API de Stripe (HU-F06 §5.3.3). El handler global lo mapea a
 * 502 {@code STRIPE_API_ERROR}. La causa raíz queda en {@link #getCause()} (típicamente una
 * {@code com.stripe.exception.StripeException} tras agotar los reintentos de Resilience4j).
 */
public class StripeApiException extends RuntimeException {

    private final String stripeErrorCode;

    public StripeApiException(String message, String stripeErrorCode, Throwable cause) {
        super(message, cause);
        this.stripeErrorCode = stripeErrorCode;
    }

    public String getStripeErrorCode() {
        return stripeErrorCode;
    }
}
