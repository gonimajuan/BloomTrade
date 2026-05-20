package co.edu.unbosque.bloomtrade.auth.dto;

/**
 * Respuesta 200 de {@code POST /api/v1/auth/login} (spec HU-F02 §6.1.1).
 *
 * <p>{@code tempSessionId} es el identificador opaco que el cliente usa en {@code /mfa/verify} y
 * {@code /mfa/resend}. {@code expiresInSeconds} es siempre 300 (5 min) al emitirse.
 */
public record LoginResponse(String tempSessionId, int expiresInSeconds) {}
