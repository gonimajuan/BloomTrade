package co.edu.unbosque.bloomtrade.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Payload de {@code POST /api/v1/auth/mfa/verify} (spec HU-F02 §6.1.2).
 *
 * <p>El {@code @Pattern} del código admite cadena vacía para que el faltante reporte solo
 * {@code VALIDATION_REQUIRED} (vía {@code @NotBlank}) sin solaparse con
 * {@code VALIDATION_INVALID_OTP} — mismo patrón que email en {@code LoginRequest}.
 */
public record MfaVerifyRequest(
        @NotBlank(message = "VALIDATION_REQUIRED") String tempSessionId,
        @NotBlank(message = "VALIDATION_REQUIRED")
                @Pattern(regexp = "^(|\\d{6})$", message = "VALIDATION_INVALID_OTP")
                String code) {}
