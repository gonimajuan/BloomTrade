package co.edu.unbosque.bloomtrade.auth.dto;

import co.edu.unbosque.bloomtrade.auth.domain.UserRole;
import co.edu.unbosque.bloomtrade.auth.domain.UserStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Respuesta 201 de registro (spec HU-F01 §6.1). Nunca incluye {@code password_hash}.
 */
public record RegisterResponse(
        UUID id,
        String email,
        String nombreCompleto,
        UserRole rol,
        UserStatus estado,
        Instant createdAt) {}
