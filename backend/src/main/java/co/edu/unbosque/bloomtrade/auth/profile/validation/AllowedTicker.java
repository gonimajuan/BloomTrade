package co.edu.unbosque.bloomtrade.auth.profile.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Valida que el string pertenece al catálogo de 25 activos del MVP (ARCHITECTURE.md §1).
 *
 * <p>Diseñada principalmente para anotar el tipo del elemento de una colección:
 * {@code List<@AllowedTicker String> tickersOfInterest}. Por eso incluye {@code TYPE_USE} en el
 * target. El {@code message} es el código SCREAMING_SNAKE (D10 HU-F01).
 *
 * <p>{@code null} se considera válido para no solapar con {@code @NotNull} cuando aplique.
 */
@Target({
    ElementType.TYPE_USE,
    ElementType.FIELD,
    ElementType.PARAMETER,
    ElementType.RECORD_COMPONENT
})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = AllowedTickerValidator.class)
public @interface AllowedTicker {

    String message() default "INVALID_TICKER";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
