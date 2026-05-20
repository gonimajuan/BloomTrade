package co.edu.unbosque.bloomtrade.auth.dto;

/**
 * Respuesta 200 de {@code POST /api/v1/auth/mfa/verify} (spec HU-F02 §6.1.2).
 *
 * <p>Decisión D18: solo se entrega access token (sin refresh ni cookie). El frontend lo guarda en
 * memoria del AuthContext; al expirar, redirige a {@code /login}.
 */
public record MfaVerifyResponse(String accessToken, int expiresIn, UserSummary user) {}
