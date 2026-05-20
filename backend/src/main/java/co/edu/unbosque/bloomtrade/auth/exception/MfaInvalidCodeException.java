package co.edu.unbosque.bloomtrade.auth.exception;

/**
 * El código OTP enviado no coincide con el almacenado en Redis (spec HU-F02 §5.3.5). Mapea a 400
 * {@code MFA_INVALID_CODE}; el handler usa {@code attemptsRemaining} para construir
 * "Código incorrecto. Intentos restantes: N." (alineado con la UI de spec §12.1).
 */
public class MfaInvalidCodeException extends RuntimeException {

    private final int attemptsRemaining;

    public MfaInvalidCodeException(int attemptsRemaining) {
        super("Código incorrecto");
        this.attemptsRemaining = attemptsRemaining;
    }

    public int getAttemptsRemaining() {
        return attemptsRemaining;
    }
}
