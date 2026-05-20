package co.edu.unbosque.bloomtrade.shared.web;

/**
 * Detalle de un error de validación de un campo concreto.
 *
 * <p>Forma parte del contrato global {@link ErrorResponse} (spec HU-F01 §6.3) y se reutiliza en
 * todos los endpoints del proyecto. {@code code} es un identificador en SCREAMING_SNAKE_CASE
 * (p.ej. {@code WEAK_PASSWORD}); {@code message} es el texto legible en español.
 */
public record FieldErrorItem(String field, String code, String message) {}
