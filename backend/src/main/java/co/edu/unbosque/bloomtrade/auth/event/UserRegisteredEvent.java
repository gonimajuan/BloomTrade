package co.edu.unbosque.bloomtrade.auth.event;

import co.edu.unbosque.bloomtrade.auth.domain.UserRole;
import java.util.UUID;

/**
 * Evento de aplicación publicado por {@code RegisterService} tras crear el usuario. Lo procesa
 * {@code RegistrationEventListener} <strong>post-commit</strong> (spec HU-F01 §5.1 pasos 13–14).
 */
public record UserRegisteredEvent(
        UUID userId, String email, String nombreCompleto, UserRole rol, String ipOrigin) {}
