package co.edu.unbosque.bloomtrade.auth.exception;

/**
 * El email del request ya existe en {@code app.users} (comparación case-insensitive).
 * Mapea a HTTP 409 {@code EMAIL_ALREADY_REGISTERED} (spec HU-F01 §5.3.1).
 */
public class EmailAlreadyRegisteredException extends RuntimeException {

    private final transient String attemptedEmail;

    public EmailAlreadyRegisteredException(String attemptedEmail) {
        super("Email ya registrado");
        this.attemptedEmail = attemptedEmail;
    }

    public String getAttemptedEmail() {
        return attemptedEmail;
    }
}
