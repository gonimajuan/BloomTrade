package co.edu.unbosque.bloomtrade.auth.exception;

/**
 * Cuarto resend solicitado tras agotar los 3 permitidos (spec HU-F02 §5.3.10). Mapea a 429
 * {@code MAX_RESENDS_EXCEEDED}; el servicio invalida la sesión temporal antes de lanzar.
 */
public class MaxResendsExceededException extends RuntimeException {

    public MaxResendsExceededException() {
        super("Máximo de reenvíos alcanzado");
    }
}
