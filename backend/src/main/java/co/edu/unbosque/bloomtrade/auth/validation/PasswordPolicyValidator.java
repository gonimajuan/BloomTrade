package co.edu.unbosque.bloomtrade.auth.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Encapsula la política de password (TAC-M3, componente {@code PasswordPolicyValidator} de spec
 * HU-F01 §8.1). {@link #isStrong(String)} es la regla pura y testeable de forma unitaria.
 */
public class PasswordPolicyValidator implements ConstraintValidator<StrongPassword, String> {

    private static final int MIN_LENGTH = 10;
    private static final int MAX_LENGTH = 100;

    /** Regla pura: 10–100 chars con al menos una mayúscula, una minúscula y un dígito. */
    public static boolean isStrong(String raw) {
        if (raw == null) {
            return false;
        }
        int len = raw.length();
        if (len < MIN_LENGTH || len > MAX_LENGTH) {
            return false;
        }
        boolean upper = false;
        boolean lower = false;
        boolean digit = false;
        for (int i = 0; i < len; i++) {
            char c = raw.charAt(i);
            if (Character.isUpperCase(c)) {
                upper = true;
            } else if (Character.isLowerCase(c)) {
                lower = true;
            } else if (Character.isDigit(c)) {
                digit = true;
            }
        }
        return upper && lower && digit;
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // null/blanco lo gobierna @NotBlank (VALIDATION_REQUIRED); aquí no se solapa.
        if (value == null || value.isBlank()) {
            return true;
        }
        return isStrong(value);
    }
}
