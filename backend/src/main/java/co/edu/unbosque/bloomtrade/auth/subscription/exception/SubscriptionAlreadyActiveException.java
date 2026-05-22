package co.edu.unbosque.bloomtrade.auth.subscription.exception;

import java.time.Instant;

/**
 * El usuario ya tiene una suscripción {@code ACTIVE} y intenta crear otra (spec §5.3.1).
 * Mapea a 409 {@code SUBSCRIPTION_ALREADY_ACTIVE} con el {@code currentPeriodEnd} en el mensaje.
 */
public class SubscriptionAlreadyActiveException extends RuntimeException {

    private final Instant currentPeriodEnd;

    public SubscriptionAlreadyActiveException(Instant currentPeriodEnd) {
        super("Ya existe una suscripción activa hasta " + currentPeriodEnd);
        this.currentPeriodEnd = currentPeriodEnd;
    }

    public Instant getCurrentPeriodEnd() {
        return currentPeriodEnd;
    }
}
