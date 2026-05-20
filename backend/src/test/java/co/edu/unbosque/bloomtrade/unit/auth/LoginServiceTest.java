package co.edu.unbosque.bloomtrade.unit.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.unbosque.bloomtrade.audit.AuditEvent;
import co.edu.unbosque.bloomtrade.audit.AuditEvent.AuditResult;
import co.edu.unbosque.bloomtrade.audit.AuditEventType;
import co.edu.unbosque.bloomtrade.audit.Auditor;
import co.edu.unbosque.bloomtrade.auth.domain.DocumentType;
import co.edu.unbosque.bloomtrade.auth.domain.User;
import co.edu.unbosque.bloomtrade.auth.domain.UserStatus;
import co.edu.unbosque.bloomtrade.auth.dto.LoginRequest;
import co.edu.unbosque.bloomtrade.auth.dto.LoginResponse;
import co.edu.unbosque.bloomtrade.auth.exception.AccountLockedException;
import co.edu.unbosque.bloomtrade.auth.exception.AccountNotActiveException;
import co.edu.unbosque.bloomtrade.auth.exception.InvalidCredentialsException;
import co.edu.unbosque.bloomtrade.auth.ratelimit.LoginAttemptTracker;
import co.edu.unbosque.bloomtrade.auth.repository.UserRepository;
import co.edu.unbosque.bloomtrade.auth.service.LoginService;
import co.edu.unbosque.bloomtrade.auth.session.OtpGenerator;
import co.edu.unbosque.bloomtrade.auth.session.TempSessionManager;
import co.edu.unbosque.bloomtrade.notification.Notifier;
import co.edu.unbosque.bloomtrade.notification.dto.AccountLockedEmailCommand;
import co.edu.unbosque.bloomtrade.notification.dto.OtpEmailCommand;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class LoginServiceTest {

    private static final String EMAIL = "juan@example.com";
    private static final String IP = "203.0.113.7";
    private static final String PASSWORD = "SecurePass123";
    private static final String HASH = "$2a$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTU";
    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private LoginAttemptTracker loginAttemptTracker;
    @Mock private TempSessionManager tempSessionManager;
    @Mock private OtpGenerator otpGenerator;
    @Mock private Notifier notifier;
    @Mock private Auditor auditor;

    private LoginService service;
    private LoginRequest request;

    @BeforeEach
    void setUp() {
        service =
                new LoginService(
                        userRepository,
                        passwordEncoder,
                        loginAttemptTracker,
                        tempSessionManager,
                        otpGenerator,
                        notifier,
                        auditor);
        request = new LoginRequest(EMAIL, PASSWORD);
    }

    private User activeUser() {
        User user =
                User.register(
                        EMAIL,
                        HASH,
                        "Juan Pérez García",
                        DocumentType.CC,
                        "1020304050",
                        "+573001234567",
                        Instant.now());
        ReflectionTestUtils.setField(user, "id", USER_ID);
        return user;
    }

    private User userWithStatus(UserStatus status) {
        User user = activeUser();
        ReflectionTestUtils.setField(user, "estado", status);
        return user;
    }

    @Test
    void shouldEmitOtpAndAuditAllowedOnHappyPath() {
        User user = activeUser();
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));
        when(loginAttemptTracker.isLocked(USER_ID)).thenReturn(false);
        when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(true);
        when(otpGenerator.generate()).thenReturn("123456");
        when(tempSessionManager.createSession(any(), anyString()))
                .thenReturn("temp-session-id");

        LoginResponse response = service.login(request, IP);

        assertThat(response.tempSessionId()).isEqualTo("temp-session-id");
        assertThat(response.expiresInSeconds()).isEqualTo(300);
        verify(loginAttemptTracker).reset(USER_ID);
        ArgumentCaptor<OtpEmailCommand> cmd = ArgumentCaptor.forClass(OtpEmailCommand.class);
        verify(notifier).sendOtpEmail(cmd.capture());
        assertThat(cmd.getValue().otpCode()).isEqualTo("123456");
        assertThat(cmd.getValue().toEmail()).isEqualTo(EMAIL);

        AuditEvent event = captureAudit();
        assertThat(event.eventType()).isEqualTo(AuditEventType.LOGIN_ATTEMPT);
        assertThat(event.result()).isEqualTo(AuditResult.ALLOWED);
        assertThat(event.actorId()).isEqualTo(USER_ID.toString());
        assertThat(event.actorRole()).isEqualTo("INVESTOR");
    }

    @Test
    void shouldRejectWhenEmailNotFoundWithoutIncrementingCounter() {
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(request, IP))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(loginAttemptTracker, never()).recordFailed(any());
        verify(notifier, never()).sendOtpEmail(any());

        AuditEvent event = captureAudit();
        assertThat(event.eventType()).isEqualTo(AuditEventType.LOGIN_ATTEMPT);
        assertThat(event.result()).isEqualTo(AuditResult.DENIED);
        assertThat(event.actorId()).isNull();
        assertThat(event.details())
                .containsEntry("reason", "INVALID_CREDENTIALS")
                .containsEntry("attemptedEmail", EMAIL);
    }

    @Test
    void shouldRejectWhenAccountIsBlocked() {
        when(userRepository.findByEmailIgnoreCase(EMAIL))
                .thenReturn(Optional.of(userWithStatus(UserStatus.BLOCKED)));

        assertThatThrownBy(() -> service.login(request, IP))
                .isInstanceOf(AccountNotActiveException.class);

        AuditEvent event = captureAudit();
        assertThat(event.eventType()).isEqualTo(AuditEventType.LOGIN_ATTEMPT);
        assertThat(event.result()).isEqualTo(AuditResult.DENIED);
        assertThat(event.details())
                .containsEntry("reason", "ACCOUNT_NOT_ACTIVE")
                .containsEntry("accountStatus", "BLOCKED");
        verify(loginAttemptTracker, never()).isLocked(any());
        verify(loginAttemptTracker, never()).recordFailed(any());
    }

    @Test
    void shouldReject423WhenLockoutActive() {
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(activeUser()));
        when(loginAttemptTracker.isLocked(USER_ID)).thenReturn(true);
        when(loginAttemptTracker.lockoutSecondsRemaining(USER_ID)).thenReturn(420L);

        assertThatThrownBy(() -> service.login(request, IP))
                .isInstanceOf(AccountLockedException.class);

        verify(loginAttemptTracker, never()).recordFailed(any());
        AuditEvent event = captureAudit();
        assertThat(event.eventType()).isEqualTo(AuditEventType.LOGIN_ATTEMPT);
        assertThat(event.details()).containsEntry("reason", "ACCOUNT_LOCKED");
    }

    @Test
    void shouldRejectAndIncrementOnPasswordMismatch() {
        User user = activeUser();
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));
        when(loginAttemptTracker.isLocked(USER_ID)).thenReturn(false);
        when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(false);
        when(loginAttemptTracker.recordFailed(USER_ID)).thenReturn(1);

        assertThatThrownBy(() -> service.login(request, IP))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(loginAttemptTracker, never()).lock(any());
        verify(notifier, never()).sendAccountLockedEmail(any());
        AuditEvent event = captureAudit();
        assertThat(event.details())
                .containsEntry("reason", "INVALID_CREDENTIALS")
                .containsEntry("attemptNumber", 1);
    }

    @Test
    void shouldLockAndSendAccountLockedEmailOnThirdFailure() {
        User user = activeUser();
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));
        when(loginAttemptTracker.isLocked(USER_ID)).thenReturn(false);
        when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(false);
        when(loginAttemptTracker.recordFailed(USER_ID)).thenReturn(3);

        assertThatThrownBy(() -> service.login(request, IP))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(loginAttemptTracker).lock(USER_ID);
        ArgumentCaptor<AccountLockedEmailCommand> cmd =
                ArgumentCaptor.forClass(AccountLockedEmailCommand.class);
        verify(notifier).sendAccountLockedEmail(cmd.capture());
        assertThat(cmd.getValue().lockDurationMinutes()).isEqualTo(15);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditor, org.mockito.Mockito.atLeast(2)).record(captor.capture());
        assertThat(captor.getAllValues())
                .anyMatch(
                        e ->
                                e.eventType() == AuditEventType.ACCOUNT_LOCKED
                                        && "MAX_LOGIN_ATTEMPTS".equals(e.details().get("reason"))
                                        && Integer.valueOf(900)
                                                .equals(e.details().get("lockDurationSeconds")));
    }

    @Test
    void shouldAuditTechnicalErrorAndRethrowWhenRepositoryFails() {
        when(userRepository.findByEmailIgnoreCase(EMAIL))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> service.login(request, IP))
                .isInstanceOf(DataAccessResourceFailureException.class);

        AuditEvent event = captureAudit();
        assertThat(event.eventType()).isEqualTo(AuditEventType.LOGIN_ATTEMPT);
        assertThat(event.details())
                .containsEntry("reason", "TECHNICAL_ERROR")
                .containsEntry(
                        "errorClass", DataAccessResourceFailureException.class.getName());
    }

    private AuditEvent captureAudit() {
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditor).record(captor.capture());
        return captor.getValue();
    }
}
