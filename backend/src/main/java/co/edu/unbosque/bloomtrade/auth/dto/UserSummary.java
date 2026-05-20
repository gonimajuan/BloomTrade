package co.edu.unbosque.bloomtrade.auth.dto;

import co.edu.unbosque.bloomtrade.auth.domain.UserRole;
import java.util.UUID;

/**
 * Datos básicos del usuario autenticado (spec HU-F02 §6.1.2 / §6.3).
 *
 * <p>Se introduce en este bundle y se reutilizará en {@code /mfa/verify} (Lote D) y en cualquier
 * endpoint posterior que devuelva el "yo" autenticado (perfil, dashboard, etc.).
 */
public record UserSummary(UUID id, String email, String nombreCompleto, UserRole rol) {}
