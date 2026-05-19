package co.edu.unbosque.bloomtrade.shared.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * Cuerpo estándar de error de la API (STACK.md §9.3, spec HU-F01 §6.3).
 *
 * <p>Este esquema es <strong>compartido por todos los endpoints</strong>. Cualquier modificación
 * futura obliga a revisar todas las specs que lo referencian (spec §6.3).
 *
 * <p>{@code fieldErrors} solo se serializa cuando hay errores de validación de campo
 * (resto de respuestas lo omiten gracias a {@link JsonInclude}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        String traceId,
        List<FieldErrorItem> fieldErrors) {

    /** Error sin detalle de campos (409, 500, etc.). */
    public static ErrorResponse of(
            int status, String error, String message, String path, String traceId) {
        return new ErrorResponse(Instant.now(), status, error, message, path, traceId, null);
    }

    /** Error de validación con la lista de campos que fallaron (400). */
    public static ErrorResponse validation(
            int status,
            String error,
            String message,
            String path,
            String traceId,
            List<FieldErrorItem> fieldErrors) {
        return new ErrorResponse(Instant.now(), status, error, message, path, traceId, fieldErrors);
    }
}
