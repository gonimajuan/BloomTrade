package co.edu.unbosque.bloomtrade.auth.exception;

/**
 * Login intentado contra una cuenta con {@code lockout:{userId}} activo en Redis
 * (spec HU-F02 §5.3.3). Mapea a 423 {@code ACCOUNT_LOCKED}; el handler usa los
 * {@code secondsRemaining} para construir "Intenta de nuevo en X minutos" y el header
 * {@code Retry-After}.
 */
public class AccountLockedException extends RuntimeException {

    private final long secondsRemaining;

    public AccountLockedException(long secondsRemaining) {
        super("Cuenta bloqueada");
        this.secondsRemaining = secondsRemaining;
    }

    public long getSecondsRemaining() {
        return secondsRemaining;
    }
}
