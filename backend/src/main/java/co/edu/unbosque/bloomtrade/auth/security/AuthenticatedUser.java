package co.edu.unbosque.bloomtrade.auth.security;

import java.util.UUID;

/**
 * Principal del SecurityContext una vez validado el access token (spec HU-F02 §8.3).
 * Inmutable; expuesto por {@link JwtService#validate} y consumido por filtros + controllers
 * vía {@code @AuthenticationPrincipal AuthenticatedUser}.
 */
public record AuthenticatedUser(UUID userId, String role) {}
