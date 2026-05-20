package co.edu.unbosque.bloomtrade.auth.exception;

/**
 * Access token con firma corrupta, formato malformado, o que no pasa otras validaciones
 * estructurales. Mapea a 401 {@code TOKEN_INVALID} en {@code GlobalExceptionHandler}.
 */
public class TokenInvalidException extends RuntimeException {

    public TokenInvalidException(String message, Throwable cause) {
        super(message, cause);
    }
}
