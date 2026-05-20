package co.edu.unbosque.bloomtrade.auth.dto;

/**
 * Respuesta 200 de {@code POST /api/v1/auth/mfa/resend} (spec HU-F02 §6.1.3).
 *
 * <p>{@code expiresInSeconds} es el TTL del nuevo OTP (siempre 300 = 5 min). {@code resendsRemaining}
 * permite al frontend deshabilitar el botón cuando ya no queden reenvíos disponibles.
 */
public record MfaResendResponse(int expiresInSeconds, int resendsRemaining) {}
