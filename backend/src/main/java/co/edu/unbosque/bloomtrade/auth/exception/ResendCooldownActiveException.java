package co.edu.unbosque.bloomtrade.auth.exception;

/**
 * Resend solicitado antes de que pasen los 30s desde el último (spec HU-F02 §5.3.9). Mapea a 429
 * {@code RESEND_COOLDOWN_ACTIVE} con header {@code Retry-After: {secondsRemaining}}.
 */
public class ResendCooldownActiveException extends RuntimeException {

    private final long secondsRemaining;

    public ResendCooldownActiveException(long secondsRemaining) {
        super("Cooldown activo");
        this.secondsRemaining = secondsRemaining;
    }

    public long getSecondsRemaining() {
        return secondsRemaining;
    }
}
