package co.edu.unbosque.bloomtrade.unit.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.unbosque.bloomtrade.audit.AuditEvent;
import co.edu.unbosque.bloomtrade.audit.AuditEventType;
import co.edu.unbosque.bloomtrade.audit.Auditor;
import co.edu.unbosque.bloomtrade.auth.domain.DocumentType;
import co.edu.unbosque.bloomtrade.auth.domain.User;
import co.edu.unbosque.bloomtrade.auth.domain.UserRole;
import co.edu.unbosque.bloomtrade.auth.domain.UserStatus;
import co.edu.unbosque.bloomtrade.auth.dto.RegisterRequest;
import co.edu.unbosque.bloomtrade.auth.dto.RegisterResponse;
import co.edu.unbosque.bloomtrade.auth.event.UserRegisteredEvent;
import co.edu.unbosque.bloomtrade.auth.exception.EmailAlreadyRegisteredException;
import co.edu.unbosque.bloomtrade.auth.exception.RegistrationTechnicalException;
import co.edu.unbosque.bloomtrade.auth.mapper.UserMapper;
import co.edu.unbosque.bloomtrade.auth.repository.UserRepository;
import co.edu.unbosque.bloomtrade.auth.service.RegisterService;
import co.edu.unbosque.bloomtrade.portfolio.service.BalanceInitializer;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RegisterServiceTest {

    private static final String EMAIL = "juan@example.com";
    private static final String IP = "203.0.113.7";

    @Mock private UserRepository userRepository;
    @Mock private BalanceInitializer balanceInitializer;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private UserMapper userMapper;
    @Mock private Auditor auditor;
    @Mock private ApplicationEventPublisher eventPublisher;

    private RegisterService service;
    private RegisterRequest request;

    @BeforeEach
    void setUp() {
        service =
                new RegisterService(
                        userRepository,
                        balanceInitializer,
                        passwordEncoder,
                        userMapper,
                        auditor,
                        eventPublisher);
        request =
                new RegisterRequest(
                        EMAIL,
                        "SecurePass123",
                        "Juan Pérez García",
                        DocumentType.CC,
                        "1234567890",
                        "+573001234567",
                        true);
    }

    private User savedUserWithId(UUID id) {
        User user =
                User.register(
                        EMAIL,
                        "$2a$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTU",
                        "Juan Pérez García",
                        DocumentType.CC,
                        "1234567890",
                        "+573001234567",
                        Instant.now());
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Test
    void shouldPersistUserAndPublishEventWhenRegistrationSucceeds() {
        UUID id = UUID.randomUUID();
        User saved = savedUserWithId(id);
        RegisterResponse expected =
                new RegisterResponse(
                        id, EMAIL, "Juan Pérez García", UserRole.INVESTOR, UserStatus.ACTIVE,
                        Instant.now());
        when(userRepository.existsByEmailIgnoreCase(EMAIL)).thenReturn(false);
        when(passwordEncoder.encode("SecurePass123")).thenReturn("$2a$12$hash");
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(saved);
        when(userMapper.toResponse(saved)).thenReturn(expected);

        RegisterResponse result = service.register(request, IP);

        assertThat(result).isEqualTo(expected);
        verify(balanceInitializer).initializeBalance(id);
        ArgumentCaptor<UserRegisteredEvent> evt =
                ArgumentCaptor.forClass(UserRegisteredEvent.class);
        verify(eventPublisher).publishEvent(evt.capture());
        assertThat(evt.getValue().userId()).isEqualTo(id);
        assertThat(evt.getValue().ipOrigin()).isEqualTo(IP);
        assertThat(evt.getValue().rol()).isEqualTo(UserRole.INVESTOR);
        verify(auditor, never()).record(any()); // USER_REGISTERED lo emite el listener post-commit
    }

    @Test
    void shouldRejectAndAuditWhenEmailAlreadyExists() {
        when(userRepository.existsByEmailIgnoreCase(EMAIL)).thenReturn(true);

        assertThatThrownBy(() -> service.register(request, IP))
                .isInstanceOf(EmailAlreadyRegisteredException.class);

        ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditor).record(audit.capture());
        assertThat(audit.getValue().eventType())
                .isEqualTo(AuditEventType.USER_REGISTRATION_FAILED);
        assertThat(audit.getValue().details())
                .containsEntry("reason", "EMAIL_DUPLICATE")
                .containsEntry("attemptedEmail", EMAIL);
        verify(userRepository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void shouldMapUniqueIndexRaceToEmailDuplicate() {
        when(userRepository.existsByEmailIgnoreCase(EMAIL)).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("$2a$12$hash");
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("dup key"));

        assertThatThrownBy(() -> service.register(request, IP))
                .isInstanceOf(EmailAlreadyRegisteredException.class);

        ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditor).record(audit.capture());
        assertThat(audit.getValue().details()).containsEntry("reason", "EMAIL_DUPLICATE");
    }

    @Test
    void shouldMapDataAccessFailureToTechnicalError() {
        when(userRepository.existsByEmailIgnoreCase(EMAIL)).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("$2a$12$hash");
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> service.register(request, IP))
                .isInstanceOf(RegistrationTechnicalException.class);

        ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditor).record(audit.capture());
        assertThat(audit.getValue().eventType())
                .isEqualTo(AuditEventType.USER_REGISTRATION_FAILED);
        assertThat(audit.getValue().details())
                .containsEntry("reason", "TECHNICAL_ERROR")
                .containsEntry(
                        "errorClass", DataAccessResourceFailureException.class.getName());
    }
}
