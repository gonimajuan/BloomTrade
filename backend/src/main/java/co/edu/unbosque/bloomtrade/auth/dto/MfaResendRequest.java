package co.edu.unbosque.bloomtrade.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** Payload de {@code POST /api/v1/auth/mfa/resend} (spec HU-F02 §6.1.3). */
public record MfaResendRequest(
        @NotBlank(message = "VALIDATION_REQUIRED") String tempSessionId) {}
