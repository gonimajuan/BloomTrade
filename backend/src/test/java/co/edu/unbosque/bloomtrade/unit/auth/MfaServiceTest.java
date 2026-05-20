package co.edu.unbosque.bloomtrade.unit.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.unbosque.bloomtrade.audit.AuditEvent;
import co.edu.unbosque.bloomtrade.audit.AuditEventType;
import co.edu.unbosque.bloomtrade.audit.Auditor;
import co.edu.unbosque.bloomtrade.auth.domain.DocumentType;
import co.edu.unbosque.bloomtrade.auth.domain.User;
import co.edu.unbosque.bloomtrade.auth.dto.MfaResendRequest;
import co.edu.unbosque.bloomtrade.auth.dto.MfaResendResponse;
import co.edu.unbosque.bloomtrade.auth.dto.MfaVerifyRequest;
import co.edu.unbosque.bloomtrade.auth.dto.MfaVerifyResponse;
import co.edu.unbosque.bloomtrade.auth.exception.MaxResendsExceededException;
import co.edu.unbosque.bloomtrade.auth.exception.MfaCodeExpiredException;
import co.edu.unbosque.bloomtrade.auth.exception.MfaInvalidCodeException;
import co.edu.unbosque.bloomtrade.auth.exception.MfaSessionInvalidatedException;
import co.edu.unbosque.bloomtrade.auth.exception.ResendCooldownActiveException;
import co.edu.unbosque.bloomtrade.auth.exception.TempSessionInvalidException;
import co.edu.unbosque.bloomtrade.auth.ratelimit.MfaAttemptTracker;
import co.edu.unbosque.bloomtrade.auth.repository.UserRepository;
import co.edu.unbosque.bloomtrade.auth.security.TokenIssuer;
import co.edu.unbosque.bloomtrade.auth.security.TokenIssuer.IssuedAccessToken;
import co.edu.unbosque.bloomtrade.auth.service.MfaService;
import co.edu.unbosque.bloomtrade.auth.session.OtpGenerator;
import co.edu.unbosque.bloomtrade.auth.session.TempSessionData;
import co.edu.unbosque.bloomtrade.auth.session.TempSessionManager;
import co.edu.unbosque.bloomtrade.notification.Notifier;
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
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MfaServiceTest {

    private static final String SESSION_ID = "7f3a2c1b-9e4d-4f6e-8a7d-2b9c1e5f8a3b";
    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final String EMAIL = "juan@example.com";
    private static final String IP = "203.0.113.7";

    @Mock private TempSessionManager tempSessionManager;
    @Mock private MfaAttemptTracker mfaAttemptTracker;
    @Mock private OtpGenerator otpGenerator;
    @Mock private TokenIssuer tokenIssuer;
    @Mock private UserRepository userRepository;
    @Mock private Notifier notifier;
    @Mock private Auditor auditor;

    private MfaService service;

    @BeforeEach
    void setUp() {
        service =
                new MfaService(
                        tempSessionManager,
                        mfaAttemptTracker,
                        otpGenerator,
                        tokenIssuer,
                        userRepository,
                        notifier,
                        auditor);
    }

    private TempSessionData session() {
        return new TempSessionData(USER_ID.toString(), EMAIL, "INVESTOR", Instant.now());
    }

    private User userInBd() {
        User user =
                User.register(
                        EMAIL,
                        "$2a$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTU",
                        "Juan Pérez García",
                        DocumentType.CC,
                        "1020304050",
                        "+573001234567",
                        Instant.now());
        ReflectionTestUtils.setField(user, "id", USER_ID);
        return user;
    }

    // ── verify ──────────────────────────────────────────────────────────────────

    @Test
    void shouldIssueAccessTokenAndInvalidateSessionOnHappyPath() {
        MfaVerifyRequest request = new MfaVerifyRequest(SESSION_ID, "123456");
        when(tempSessionManager.getSession(SESSION_ID)).thenReturn(Optional.of(session()));
        when(tempSessionManager.getOtp(SESSION_ID)).thenReturn(Optional.of("123456"));
        when(otpGenerator.matches("123456", "123456")).thenReturn(true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(userInBd()));
        when(tokenIssuer.issueAccessToken(USER_ID, "INVESTOR"))
                .thenReturn(new IssuedAccessToken("jwt.signed.token", 900));

        MfaVerifyResponse response = service.verify(request, IP);

        assertThat(response.accessToken()).isEqualTo("jwt.signed.token");
        assertThat(response.expiresIn()).isEqualTo(900);
        assertThat(response.user().email()).isEqualTo(EMAIL);
        verify(tempSessionManager).invalidate(SESSION_ID);
        AuditEvent event = captureAudit();
        assertThat(event.eventType()).isEqualTo(AuditEventType.MFA_VERIFIED);
        assertThat(event.details()).containsKey("tempSessionDurationMs");
    }

    @Test
    void shouldRejectAndAuditWhenTempSessionMissing() {
        MfaVerifyRequest request = new MfaVerifyRequest(SESSION_ID, "123456");
        when(tempSessionManager.getSession(SESSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify(request, IP))
                .isInstanceOf(TempSessionInvalidException.class);

        verify(tokenIssuer, never()).issueAccessToken(any(), anyString());
        AuditEvent event = captureAudit();
        assertThat(event.eventType()).isEqualTo(AuditEventType.MFA_FAILED);
        assertThat(event.details()).containsEntry("reason", "SESSION_EXPIRED");
    }

    @Test
    void shouldRejectAndAuditWhenOtpMissing() {
        MfaVerifyRequest request = new MfaVerifyRequest(SESSION_ID, "123456");
        when(tempSessionManager.getSession(SESSION_ID)).thenReturn(Optional.of(session()));
        when(tempSessionManager.getOtp(SESSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify(request, IP))
                .isInstanceOf(MfaCodeExpiredException.class);

        AuditEvent event = captureAudit();
        assertThat(event.eventType()).isEqualTo(AuditEventType.MFA_FAILED);
        assertThat(event.details()).containsEntry("reason", "CODE_EXPIRED");
    }

    @Test
    void shouldIncrementAttemptsAndKeepSessionWhenOtpMismatchUnderMax() {
        MfaVerifyRequest request = new MfaVerifyRequest(SESSION_ID, "999999");
        when(tempSessionManager.getSession(SESSION_ID)).thenReturn(Optional.of(session()));
        when(tempSessionManager.getOtp(SESSION_ID)).thenReturn(Optional.of("123456"));
        when(otpGenerator.matches("999999", "123456")).thenReturn(false);
        when(mfaAttemptTracker.recordFailed(SESSION_ID)).thenReturn(1);

        assertThatThrownBy(() -> service.verify(request, IP))
                .isInstanceOf(MfaInvalidCodeException.class)
                .hasFieldOrPropertyWithValue("attemptsRemaining", 2);

        verify(tempSessionManager, never()).invalidate(any());
        AuditEvent event = captureAudit();
        assertThat(event.eventType()).isEqualTo(AuditEventType.MFA_FAILED);
        assertThat(event.details())
                .containsEntry("reason", "INVALID_CODE")
                .containsEntry("attemptNumber", 1);
    }

    @Test
    void shouldInvalidateSessionOnThirdFailedOtp() {
        MfaVerifyRequest request = new MfaVerifyRequest(SESSION_ID, "999999");
        when(tempSessionManager.getSession(SESSION_ID)).thenReturn(Optional.of(session()));
        when(tempSessionManager.getOtp(SESSION_ID)).thenReturn(Optional.of("123456"));
        when(otpGenerator.matches("999999", "123456")).thenReturn(false);
        when(mfaAttemptTracker.recordFailed(SESSION_ID)).thenReturn(3);

        assertThatThrownBy(() -> service.verify(request, IP))
                .isInstanceOf(MfaSessionInvalidatedException.class);

        verify(tempSessionManager).invalidate(SESSION_ID);
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditor, org.mockito.Mockito.atLeast(2)).record(captor.capture());
        assertThat(captor.getAllValues())
                .anyMatch(
                        e ->
                                e.eventType() == AuditEventType.MFA_SESSION_INVALIDATED
                                        && "MAX_ATTEMPTS".equals(e.details().get("reason")));
    }

    // ── resend ──────────────────────────────────────────────────────────────────

    @Test
    void shouldResendOtpAndAuditOnHappyPath() {
        MfaResendRequest request = new MfaResendRequest(SESSION_ID);
        when(tempSessionManager.getSession(SESSION_ID)).thenReturn(Optional.of(session()));
        when(mfaAttemptTracker.getResendCount(SESSION_ID)).thenReturn(0);
        when(mfaAttemptTracker.isOnCooldown(SESSION_ID)).thenReturn(false);
        when(otpGenerator.generate()).thenReturn("654321");
        when(mfaAttemptTracker.recordResend(SESSION_ID)).thenReturn(1);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(userInBd()));

        MfaResendResponse response = service.resend(request, IP);

        assertThat(response.expiresInSeconds()).isEqualTo(300);
        assertThat(response.resendsRemaining()).isEqualTo(2);
        verify(tempSessionManager).replaceOtp(SESSION_ID, "654321");
        verify(mfaAttemptTracker).setCooldown(SESSION_ID);
        ArgumentCaptor<OtpEmailCommand> cmd = ArgumentCaptor.forClass(OtpEmailCommand.class);
        verify(notifier).sendOtpEmail(cmd.capture());
        assertThat(cmd.getValue().otpCode()).isEqualTo("654321");
        AuditEvent event = captureAudit();
        assertThat(event.eventType()).isEqualTo(AuditEventType.MFA_RESEND_REQUESTED);
        assertThat(event.details()).containsEntry("resendNumber", 1);
    }

    @Test
    void shouldRejectResendWhenSessionMissing() {
        MfaResendRequest request = new MfaResendRequest(SESSION_ID);
        when(tempSessionManager.getSession(SESSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resend(request, IP))
                .isInstanceOf(TempSessionInvalidException.class);

        verify(notifier, never()).sendOtpEmail(any());
    }

    @Test
    void shouldRejectResendWhenCooldownActive() {
        MfaResendRequest request = new MfaResendRequest(SESSION_ID);
        when(tempSessionManager.getSession(SESSION_ID)).thenReturn(Optional.of(session()));
        when(mfaAttemptTracker.getResendCount(SESSION_ID)).thenReturn(1);
        when(mfaAttemptTracker.isOnCooldown(SESSION_ID)).thenReturn(true);
        when(mfaAttemptTracker.cooldownSecondsRemaining(SESSION_ID)).thenReturn(18L);

        assertThatThrownBy(() -> service.resend(request, IP))
                .isInstanceOf(ResendCooldownActiveException.class)
                .hasFieldOrPropertyWithValue("secondsRemaining", 18L);

        verify(notifier, never()).sendOtpEmail(any());
        verify(tempSessionManager, never()).replaceOtp(anyString(), anyString());
    }

    @Test
    void shouldInvalidateSessionOnFourthResend() {
        MfaResendRequest request = new MfaResendRequest(SESSION_ID);
        when(tempSessionManager.getSession(SESSION_ID)).thenReturn(Optional.of(session()));
        when(mfaAttemptTracker.getResendCount(SESSION_ID)).thenReturn(3);

        assertThatThrownBy(() -> service.resend(request, IP))
                .isInstanceOf(MaxResendsExceededException.class);

        verify(tempSessionManager).invalidate(SESSION_ID);
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditor).record(captor.capture());
        assertThat(captor.getValue().eventType())
                .isEqualTo(AuditEventType.MFA_SESSION_INVALIDATED);
        assertThat(captor.getValue().details()).containsEntry("reason", "MAX_RESENDS");
    }

    private AuditEvent captureAudit() {
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditor).record(captor.capture());
        return captor.getValue();
    }
}
