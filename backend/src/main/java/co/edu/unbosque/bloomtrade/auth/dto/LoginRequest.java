package co.edu.unbosque.bloomtrade.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Payload de {@code POST /api/v1/auth/login} (spec HU-F02 §6.1.1).
 *
 * <p>El {@code message} de cada constraint es el código de error (decisión D9 del plan, alineada
 * con D10 de HU-F01); el patrón de email admite cadena vacía para que el faltante reporte solo
 * {@code VALIDATION_REQUIRED} (vía {@code @NotBlank}) sin solaparse con {@code VALIDATION_INVALID_EMAIL}.
 */
public record LoginRequest(
        @NotBlank(message = "VALIDATION_REQUIRED")
                @Size(max = 254, message = "VALIDATION_INVALID_EMAIL")
                @Pattern(
                        regexp = "^(|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})$",
                        message = "VALIDATION_INVALID_EMAIL")
                String email,
        @NotBlank(message = "VALIDATION_REQUIRED")
                @Size(max = 100, message = "VALIDATION_FAILED")
                String password) {}
