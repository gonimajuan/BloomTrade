package co.edu.unbosque.bloomtrade.auth.exception;

/**
 * Falla técnica inesperada durante la persistencia del registro (spec HU-F01 §5.3.5).
 * Mapea a HTTP 500 {@code INTERNAL_ERROR} con mensaje genérico (sin filtrar detalles al cliente).
 */
public class RegistrationTechnicalException extends RuntimeException {

    public RegistrationTechnicalException(String message, Throwable cause) {
        super(message, cause);
    }
}
