package co.edu.unbosque.bloomtrade.unit.auth.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.unbosque.bloomtrade.audit.AuditEvent;
import co.edu.unbosque.bloomtrade.audit.AuditEventType;
import co.edu.unbosque.bloomtrade.audit.Auditor;
import co.edu.unbosque.bloomtrade.auth.domain.DocumentType;
import co.edu.unbosque.bloomtrade.auth.domain.User;
import co.edu.unbosque.bloomtrade.auth.profile.domain.NotificationChannel;
import co.edu.unbosque.bloomtrade.auth.profile.dto.UpdateProfileRequest;
import co.edu.unbosque.bloomtrade.auth.profile.dto.UserProfileResponse;
import co.edu.unbosque.bloomtrade.auth.profile.mapper.UserProfileMapper;
import co.edu.unbosque.bloomtrade.auth.profile.service.ProfileService;
import co.edu.unbosque.bloomtrade.auth.repository.UserRepository;
import co.edu.unbosque.bloomtrade.auth.subscription.service.SubscriptionService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

    private static final String IP = "203.0.113.7";
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock private UserRepository userRepository;
    @Mock private UserProfileMapper mapper;
    @Mock private Auditor auditor;
    @Mock private SubscriptionService subscriptionService;

    private ProfileService service;

    @BeforeEach
    void setUp() {
        service = new ProfileService(userRepository, mapper, auditor, subscriptionService);
        // Default no-premium; los tests que lo necesiten lo override-an.
        lenient().when(subscriptionService.isPremium(USER_ID)).thenReturn(false);
    }

    private User sampleUser() {
        User user =
                User.register(
                        "juan@example.com",
                        "$2a$12$hash",
                        "Juan Pérez",
                        DocumentType.CC,
                        "1234567890",
                        "+573001234567",
                        Instant.now());
        ReflectionTestUtils.setField(user, "id", USER_ID);
        return user;
    }

    private UserProfileResponse stubResponse(User user) {
        return new UserProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getNombreCompleto(),
                user.getTipoDocumento(),
                user.getNumeroDocumento(),
                user.getTelefono(),
                user.getRol(),
                user.getEstado(),
                user.getNotificationChannel(),
                user.getTickersOfInterest(),
                false, // isPremium
                Instant.now(),
                Instant.now());
    }

    @Test
    void shouldReturnProfileWhenGetMe() {
        User user = sampleUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        UserProfileResponse expected = stubResponse(user);
        when(mapper.toResponse(user, false)).thenReturn(expected);

        UserProfileResponse result = service.getMe(USER_ID);

        assertThat(result).isSameAs(expected);
        verify(auditor, never()).record(any());
    }

    @Test
    void shouldUpdateSingleFieldAndAuditPROFILE_UPDATED() {
        User user = sampleUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(mapper.toResponse(user, false)).thenReturn(stubResponse(user));
        UpdateProfileRequest req =
                new UpdateProfileRequest("Juan Carlos Pérez", null, null, null);

        service.updateMe(USER_ID, req, IP);

        assertThat(user.getNombreCompleto()).isEqualTo("Juan Carlos Pérez");
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditor, times(1)).record(captor.capture());
        AuditEvent emitted = captor.getValue();
        assertThat(emitted.eventType()).isEqualTo(AuditEventType.PROFILE_UPDATED);
        assertThat(emitted.actorId()).isEqualTo(USER_ID.toString());
        @SuppressWarnings("unchecked")
        List<String> changedFields = (List<String>) emitted.details().get("changedFields");
        assertThat(changedFields).containsExactly("nombreCompleto");
    }

    @Test
    void shouldEmitTwoEventsWhenNotificationChannelChanges() {
        User user = sampleUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(mapper.toResponse(user, false)).thenReturn(stubResponse(user));
        UpdateProfileRequest req =
                new UpdateProfileRequest(null, null, NotificationChannel.WHATSAPP, null);

        service.updateMe(USER_ID, req, IP);

        assertThat(user.getNotificationChannel()).isEqualTo(NotificationChannel.WHATSAPP);
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditor, times(2)).record(captor.capture());
        List<AuditEvent> events = captor.getAllValues();
        assertThat(events.get(0).eventType()).isEqualTo(AuditEventType.PROFILE_UPDATED);
        assertThat(events.get(1).eventType())
                .isEqualTo(AuditEventType.NOTIFICATION_CHANNEL_CHANGED);
        assertThat(events.get(1).details())
                .containsEntry("from", "EMAIL")
                .containsEntry("to", "WHATSAPP");
    }

    @Test
    void shouldUpdateMultipleFieldsAndListAllChangedFields() {
        User user = sampleUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(mapper.toResponse(user, false)).thenReturn(stubResponse(user));
        UpdateProfileRequest req =
                new UpdateProfileRequest(
                        "Juan Carlos",
                        "+573109876543",
                        NotificationChannel.SMS,
                        List.of("AAPL", "MSFT"));

        service.updateMe(USER_ID, req, IP);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditor, times(2)).record(captor.capture());
        @SuppressWarnings("unchecked")
        List<String> changed = (List<String>) captor.getAllValues().get(0).details().get("changedFields");
        assertThat(changed)
                .containsExactlyInAnyOrder(
                        "nombreCompleto",
                        "telefono",
                        "notificationChannel",
                        "tickersOfInterest");
    }

    @Test
    void shouldBeIdempotentWhenNoFieldEffectivelyChanges() {
        User user = sampleUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(mapper.toResponse(user, false)).thenReturn(stubResponse(user));
        // Mismo nombre que ya tiene + null en el resto = no-op
        UpdateProfileRequest req =
                new UpdateProfileRequest(user.getNombreCompleto(), null, null, null);

        service.updateMe(USER_ID, req, IP);

        verify(auditor, never()).record(any());
    }

    @Test
    void shouldNotLeakPiiInPROFILE_UPDATEDDetails() {
        User user = sampleUser();
        String originalName = user.getNombreCompleto();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(mapper.toResponse(user, false)).thenReturn(stubResponse(user));
        UpdateProfileRequest req =
                new UpdateProfileRequest("Juan Carlos Pérez García", "+573109876543", null, null);

        service.updateMe(USER_ID, req, IP);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditor).record(captor.capture());
        // El detail debe contener solo nombres de campos, NUNCA los valores PII.
        String detailsStr = captor.getValue().details().toString();
        assertThat(detailsStr)
                .as("detail no debe contener valores PII del nombre")
                .doesNotContain("Juan Carlos Pérez García")
                .doesNotContain(originalName);
        assertThat(detailsStr)
                .as("detail no debe contener el nuevo número de teléfono")
                .doesNotContain("+573109876543");
    }

    @Test
    void shouldAuditPROFILE_UPDATE_FAILEDAndRethrowWhenDataAccessException() {
        User user = sampleUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        // Forzamos error en el mapper post-update para simular fallo BD.
        DataAccessResourceFailureException dbError =
                new DataAccessResourceFailureException("Postgres unreachable");
        when(mapper.toResponse(user, false)).thenThrow(dbError);
        UpdateProfileRequest req =
                new UpdateProfileRequest("Juan Carlos", null, null, null);

        assertThatThrownBy(() -> service.updateMe(USER_ID, req, IP)).isSameAs(dbError);

        // D21: el flujo emite PROFILE_UPDATED antes del mapper (que es donde falla) y luego
        // PROFILE_UPDATE_FAILED en el catch. Auditoría post-commit sería over-engineering MVP.
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditor, times(2)).record(captor.capture());
        List<AuditEvent> events = captor.getAllValues();
        assertThat(events.get(0).eventType()).isEqualTo(AuditEventType.PROFILE_UPDATED);
        assertThat(events.get(1).eventType()).isEqualTo(AuditEventType.PROFILE_UPDATE_FAILED);
        assertThat(events.get(1).details())
                .containsEntry("reason", "TECHNICAL_ERROR")
                .containsEntry("errorClass", DataAccessResourceFailureException.class.getName());
    }

    @Test
    void shouldThrowWhenUserNotFound() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMe(USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(USER_ID.toString());
        verify(auditor, never()).record(any());
    }
}
