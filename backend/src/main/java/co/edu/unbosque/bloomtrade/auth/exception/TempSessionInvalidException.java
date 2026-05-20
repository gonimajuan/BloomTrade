package co.edu.unbosque.bloomtrade.auth.exception;

/**
 * El {@code tempSessionId} enviado no existe en Redis (TTL expiró o nunca fue válido, spec
 * HU-F02 §5.3.8). Mapea a 401 {@code TEMP_SESSION_INVALID}; el usuario debe reiniciar el login.
 */
public class TempSessionInvalidException extends RuntimeException {

    public TempSessionInvalidException() {
        super("Sesión temporal expirada o inexistente");
    }
}
