package co.edu.unbosque.bloomtrade.auth.exception;

/**
 * Access token presentado pero con {@code exp} en el pasado. Mapea a 401 {@code TOKEN_EXPIRED}
 * en {@code GlobalExceptionHandler} (spec HU-F02 §5.3.13 / D9 del plan).
 */
public class TokenExpiredException extends RuntimeException {

    public TokenExpiredException(String message, Throwable cause) {
        super(message, cause);
    }
}
