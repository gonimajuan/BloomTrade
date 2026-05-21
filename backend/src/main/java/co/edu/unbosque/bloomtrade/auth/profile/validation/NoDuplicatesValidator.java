package co.edu.unbosque.bloomtrade.auth.profile.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Collection;
import java.util.HashSet;

/**
 * Encapsula la regla pura {@link #hasNoDuplicates(Collection)} para que pueda testearse sin el
 * runtime de Bean Validation.
 */
public class NoDuplicatesValidator implements ConstraintValidator<NoDuplicates, Collection<?>> {

    /** Regla pura: {@code null}/vacía → {@code true}; sin duplicados (por {@code equals}) → {@code true}. */
    public static boolean hasNoDuplicates(Collection<?> values) {
        if (values == null || values.isEmpty()) {
            return true;
        }
        return new HashSet<>(values).size() == values.size();
    }

    @Override
    public boolean isValid(Collection<?> value, ConstraintValidatorContext context) {
        return hasNoDuplicates(value);
    }
}
