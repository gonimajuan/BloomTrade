package co.edu.unbosque.bloomtrade.auth.validation;

import co.edu.unbosque.bloomtrade.auth.domain.DocumentType;
import co.edu.unbosque.bloomtrade.auth.dto.RegisterRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Valida {@code numeroDocumento} contra {@code tipoDocumento} (spec HU-F01 §6). {@link #matches}
 * es la regla pura y testeable de forma unitaria.
 */
public class DocumentNumberValidator
        implements ConstraintValidator<ConsistentDocumentNumber, RegisterRequest> {

    private static final String FIELD = "numeroDocumento";

    /** Regla pura: CC/CE = 6–12 dígitos; PASAPORTE = 6–15 alfanumérico. */
    public static boolean matches(DocumentType tipo, String numero) {
        // Casos incompletos los gobiernan @NotNull/@NotBlank de cada campo.
        if (tipo == null || numero == null || numero.isBlank()) {
            return true;
        }
        return switch (tipo) {
            case CC, CE -> numero.matches("\\d{6,12}");
            case PASAPORTE -> numero.matches("[A-Za-z0-9]{6,15}");
        };
    }

    @Override
    public boolean isValid(RegisterRequest req, ConstraintValidatorContext context) {
        if (req == null) {
            return true;
        }
        boolean ok = matches(req.tipoDocumento(), req.numeroDocumento());
        if (!ok) {
            context.disableDefaultConstraintViolation();
            context
                    .buildConstraintViolationWithTemplate(
                            context.getDefaultConstraintMessageTemplate())
                    .addPropertyNode(FIELD)
                    .addConstraintViolation();
        }
        return ok;
    }
}
