package co.edu.unbosque.bloomtrade.auth.subscription.exception;

/**
 * El usuario nunca pasó por un checkout — su {@code stripe_customer_id} es {@code NULL} y no
 * puede abrir el Customer Portal (spec v1.2 §5.3.7). Mapea a 409 {@code NO_STRIPE_CUSTOMER}.
 */
public class NoStripeCustomerException extends RuntimeException {

    public NoStripeCustomerException() {
        super("El usuario no tiene Stripe customer asociado");
    }
}
