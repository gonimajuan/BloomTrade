package co.edu.unbosque.bloomtrade.auth.profile.service;

import co.edu.unbosque.bloomtrade.audit.AuditEvent;
import co.edu.unbosque.bloomtrade.audit.AuditEvent.AuditResult;
import co.edu.unbosque.bloomtrade.audit.AuditEventType;
import co.edu.unbosque.bloomtrade.audit.Auditor;
import co.edu.unbosque.bloomtrade.auth.domain.User;
import co.edu.unbosque.bloomtrade.auth.profile.domain.NotificationChannel;
import co.edu.unbosque.bloomtrade.auth.profile.dto.UpdateProfileRequest;
import co.edu.unbosque.bloomtrade.auth.profile.dto.UserProfileResponse;
import co.edu.unbosque.bloomtrade.auth.profile.mapper.UserProfileMapper;
import co.edu.unbosque.bloomtrade.auth.repository.UserRepository;
import co.edu.unbosque.bloomtrade.auth.subscription.service.SubscriptionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orquesta lectura y actualización parcial del perfil del usuario autenticado
 * (spec HU-F04+F20 §5.1).
 *
 * <p><strong>PATCH parcial (D7):</strong> el service captura un {@link Snapshot} antes de aplicar
 * el {@link User#applyProfileUpdate} y compara para producir la lista de campos efectivamente
 * cambiados. Si está vacía → no se emite audit y la respuesta es 200 con el perfil sin tocar (D17
 * idempotencia). Si hay cambios → se emite {@code PROFILE_UPDATED} con {@code details.changedFields}
 * (SOLO nombres, no valores — D18 anti-PII). Si {@code notificationChannel} es uno de los cambios,
 * además se emite {@code NOTIFICATION_CHANNEL_CHANGED} con {@code from}/{@code to} (enums no-PII).
 */
@Service
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);
    private static final String RESOURCE = "/api/v1/me";

    private final UserRepository userRepository;
    private final UserProfileMapper userProfileMapper;
    private final Auditor auditor;
    private final SubscriptionService subscriptionService;

    public ProfileService(
            UserRepository userRepository,
            UserProfileMapper userProfileMapper,
            Auditor auditor,
            SubscriptionService subscriptionService) {
        this.userRepository = userRepository;
        this.userProfileMapper = userProfileMapper;
        this.auditor = auditor;
        this.subscriptionService = subscriptionService;
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getMe(UUID userId) {
        User user = loadOrThrow(userId);
        return userProfileMapper.toResponse(user, subscriptionService.isPremium(userId));
    }

    @Transactional
    public UserProfileResponse updateMe(UUID userId, UpdateProfileRequest req, String ipOrigin) {
        User user = loadOrThrow(userId);
        Snapshot before = Snapshot.of(user);

        try {
            user.applyProfileUpdate(
                    req.nombreCompleto(),
                    req.telefono(),
                    req.notificationChannel(),
                    req.tickersOfInterest());

            Snapshot after = Snapshot.of(user);
            List<String> changedFields = before.diff(after);

            if (changedFields.isEmpty()) {
                return userProfileMapper.toResponse(user, subscriptionService.isPremium(userId));
            }

            // Hibernate detecta el dirty state y emite UPDATE al commit.
            auditor.record(
                    AuditEvent.builder()
                            .eventType(AuditEventType.PROFILE_UPDATED)
                            .resource(RESOURCE)
                            .result(AuditResult.ALLOWED)
                            .actorId(userId.toString())
                            .actorRole(user.getRol().name())
                            .ipOrigin(ipOrigin)
                            .detail("changedFields", changedFields)
                            .build());

            if (changedFields.contains("notificationChannel")) {
                auditor.record(
                        AuditEvent.builder()
                                .eventType(AuditEventType.NOTIFICATION_CHANNEL_CHANGED)
                                .resource(RESOURCE)
                                .result(AuditResult.ALLOWED)
                                .actorId(userId.toString())
                                .actorRole(user.getRol().name())
                                .ipOrigin(ipOrigin)
                                .detail("from", before.notificationChannel.name())
                                .detail("to", after.notificationChannel.name())
                                .build());
            }

            return userProfileMapper.toResponse(user, subscriptionService.isPremium(userId));
        } catch (DataAccessException e) {
            log.error("Error técnico actualizando perfil del usuario {}", userId, e);
            auditor.record(
                    AuditEvent.builder()
                            .eventType(AuditEventType.PROFILE_UPDATE_FAILED)
                            .resource(RESOURCE)
                            .result(AuditResult.DENIED)
                            .actorId(userId.toString())
                            .ipOrigin(ipOrigin)
                            .detail("reason", "TECHNICAL_ERROR")
                            .detail("errorClass", e.getClass().getName())
                            .build());
            throw e;
        }
    }

    private User loadOrThrow(UUID userId) {
        return userRepository
                .findById(userId)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Usuario del JWT no existe en BD: " + userId));
    }

    /**
     * Instantánea inmutable del estado editable del perfil para comparar pre/post mutación. Lista
     * de tickers se copia para sobrevivir a la mutación del agregado.
     */
    private record Snapshot(
            String nombreCompleto,
            String telefono,
            NotificationChannel notificationChannel,
            List<String> tickersOfInterest) {

        static Snapshot of(User u) {
            return new Snapshot(
                    u.getNombreCompleto(),
                    u.getTelefono(),
                    u.getNotificationChannel(),
                    new ArrayList<>(u.getTickersOfInterest()));
        }

        List<String> diff(Snapshot other) {
            List<String> changed = new ArrayList<>(4);
            if (!Objects.equals(this.nombreCompleto, other.nombreCompleto)) {
                changed.add("nombreCompleto");
            }
            if (!Objects.equals(this.telefono, other.telefono)) {
                changed.add("telefono");
            }
            if (!Objects.equals(this.notificationChannel, other.notificationChannel)) {
                changed.add("notificationChannel");
            }
            if (!Objects.equals(this.tickersOfInterest, other.tickersOfInterest)) {
                changed.add("tickersOfInterest");
            }
            return changed;
        }
    }
}
