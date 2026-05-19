package co.edu.unbosque.bloomtrade.auth.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Constraint a nivel de clase (decisión D9): valida {@code numeroDocumento} según
 * {@code tipoDocumento} (spec HU-F01 §6): CC/CE = 6–12 dígitos; PASAPORTE = 6–15 alfanumérico.
 * El error se adjunta al campo {@code numeroDocumento} con código
 * {@code VALIDATION_INVALID_DOCUMENT_NUMBER} (D10).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = DocumentNumberValidator.class)
public @interface ConsistentDocumentNumber {

    String message() default "VALIDATION_INVALID_DOCUMENT_NUMBER";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
