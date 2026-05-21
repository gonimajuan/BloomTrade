package co.edu.unbosque.bloomtrade.auth.profile.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Valida que una colección no contiene elementos duplicados (según {@code equals}).
 *
 * <p>{@code null} y colecciones vacías se consideran válidas. El {@code message} es el código
 * SCREAMING_SNAKE (D10 HU-F01).
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NoDuplicatesValidator.class)
public @interface NoDuplicates {

    String message() default "DUPLICATE_TICKERS";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
