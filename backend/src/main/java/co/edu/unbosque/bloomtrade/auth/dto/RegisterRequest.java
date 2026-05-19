package co.edu.unbosque.bloomtrade.auth.dto;

import co.edu.unbosque.bloomtrade.auth.domain.DocumentType;
import co.edu.unbosque.bloomtrade.auth.validation.ConsistentDocumentNumber;
import co.edu.unbosque.bloomtrade.auth.validation.StrongPassword;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Payload de {@code POST /api/v1/auth/register} (spec HU-F01 §6.1). El {@code message} de cada
 * constraint es el código de error (decisión D10); el patrón admite cadena vacía para que el
 * faltante reporte solo {@code VALIDATION_REQUIRED} (vía {@code @NotBlank}) sin solaparse.
 */
@ConsistentDocumentNumber
public record RegisterRequest(
        @NotBlank(message = "VALIDATION_REQUIRED")
                @Size(max = 254, message = "VALIDATION_INVALID_EMAIL")
                @Pattern(
                        regexp = "^(|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})$",
                        message = "VALIDATION_INVALID_EMAIL")
                String email,
        @NotBlank(message = "VALIDATION_REQUIRED") @StrongPassword String password,
        @NotBlank(message = "VALIDATION_REQUIRED")
                @Pattern(
                        regexp = "^(|[\\p{L} ]{3,100})$",
                        message = "VALIDATION_INVALID_NAME")
                String nombreCompleto,
        @NotNull(message = "VALIDATION_REQUIRED") DocumentType tipoDocumento,
        @NotBlank(message = "VALIDATION_REQUIRED") String numeroDocumento,
        @NotBlank(message = "VALIDATION_REQUIRED")
                @Pattern(
                        regexp = "^(|\\+[1-9]\\d{1,14})$",
                        message = "VALIDATION_INVALID_PHONE")
                String telefono,
        @AssertTrue(message = "TERMS_NOT_ACCEPTED") boolean aceptaTerminos) {}
