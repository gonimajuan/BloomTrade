package co.edu.unbosque.bloomtrade.auth.exception;

/**
 * La sesión temporal fue invalidada por el sistema tras 3 fallos de OTP o 3 reenvíos
 * (spec HU-F02 §5.3.7 / §5.3.10). Mapea a 403 {@code MFA_SESSION_INVALIDATED}; el usuario debe
 * volver a {@code /login}.
 */
public class MfaSessionInvalidatedException extends RuntimeException {

    public MfaSessionInvalidatedException() {
        super("Sesión temporal invalidada");
    }
}
