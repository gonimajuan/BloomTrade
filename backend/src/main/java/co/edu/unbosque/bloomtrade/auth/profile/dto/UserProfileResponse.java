package co.edu.unbosque.bloomtrade.auth.profile.dto;

import co.edu.unbosque.bloomtrade.auth.domain.DocumentType;
import co.edu.unbosque.bloomtrade.auth.domain.UserRole;
import co.edu.unbosque.bloomtrade.auth.domain.UserStatus;
import co.edu.unbosque.bloomtrade.auth.profile.domain.NotificationChannel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Perfil completo del usuario autenticado — payload de {@code GET /api/v1/me} y de la respuesta
 * exitosa de {@code PATCH /api/v1/me} (spec HU-F04+F20 §6.1).
 *
 * <p><strong>No contiene {@code passwordHash}.</strong> El {@code UserProfileMapper} (MapStruct)
 * sólo proyecta los campos aquí declarados; cualquier filtración accidental se detecta en
 * {@code UserProfileMapperTest} via inspección del JSON serializado (D16).
 *
 * <p>El campo {@code isPremium} se agrega en HU-F06 (Día 4) — fuera de scope de este bundle (D10).
 */
public record UserProfileResponse(
        UUID id,
        String email,
        String nombreCompleto,
        DocumentType tipoDocumento,
        String numeroDocumento,
        String telefono,
        UserRole rol,
        UserStatus estado,
        NotificationChannel notificationChannel,
        List<String> tickersOfInterest,
        boolean isPremium,
        Instant createdAt,
        Instant updatedAt) {}
