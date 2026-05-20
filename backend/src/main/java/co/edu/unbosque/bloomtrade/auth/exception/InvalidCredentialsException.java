package co.edu.unbosque.bloomtrade.auth.exception;

/**
 * Email no registrado o password incorrecto (spec HU-F02 §5.3.1). Mapea a 401
 * {@code INVALID_CREDENTIALS} con mensaje genérico en {@code GlobalExceptionHandler} — nunca se
 * distingue entre "email no existe" y "password incorrecto" (prevención de account enumeration).
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Credenciales inválidas");
    }
}
