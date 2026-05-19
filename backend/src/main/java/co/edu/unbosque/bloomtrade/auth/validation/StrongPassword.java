package co.edu.unbosque.bloomtrade.auth.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Política de password (spec HU-F01 §5.3.3 / §6): 10–100 chars, ≥1 mayúscula, ≥1 minúscula,
 * ≥1 número. Encapsulada en {@link PasswordPolicyValidator} (TAC-M3). El {@code message} es el
 * código de error (decisión D10): se resuelve a texto humano en el GlobalExceptionHandler.
 *
 * <p>{@code null}/blanco se consideran válidos aquí para que {@code @NotBlank} reporte
 * {@code VALIDATION_REQUIRED} sin solaparse con {@code WEAK_PASSWORD}.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PasswordPolicyValidator.class)
public @interface StrongPassword {

    String message() default "WEAK_PASSWORD";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
