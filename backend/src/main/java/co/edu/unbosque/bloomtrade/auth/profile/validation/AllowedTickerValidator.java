package co.edu.unbosque.bloomtrade.auth.profile.validation;

import co.edu.unbosque.bloomtrade.auth.profile.catalog.AllowedTickers;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Delega en {@link AllowedTickers#contains(String)}. {@code null} → válido (no se solapa con otros). */
public class AllowedTickerValidator implements ConstraintValidator<AllowedTicker, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return AllowedTickers.contains(value);
    }
}
