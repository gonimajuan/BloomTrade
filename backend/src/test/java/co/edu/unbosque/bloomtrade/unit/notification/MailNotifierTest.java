package co.edu.unbosque.bloomtrade.unit.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.unbosque.bloomtrade.audit.AuditEvent;
import co.edu.unbosque.bloomtrade.audit.AuditEventType;
import co.edu.unbosque.bloomtrade.audit.Auditor;
import co.edu.unbosque.bloomtrade.notification.MailNotifier;
import co.edu.unbosque.bloomtrade.notification.dto.AccountLockedEmailCommand;
import co.edu.unbosque.bloomtrade.notification.dto.OtpEmailCommand;
import co.edu.unbosque.bloomtrade.notification.dto.WelcomeEmailCommand;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

@ExtendWith(MockitoExtension.class)
class MailNotifierTest {

    private static final WelcomeEmailCommand WELCOME_CMD =
            new WelcomeEmailCommand("uuid-123", "juan@example.com", "Juan Pérez García");
    private static final OtpEmailCommand OTP_CMD =
            new OtpEmailCommand("uuid-123", "juan@example.com", "Juan Pérez García", "123456", 5);
    private static final AccountLockedEmailCommand LOCKED_CMD =
            new AccountLockedEmailCommand("uuid-123", "juan@example.com", "Juan Pérez García", 15);

    @Mock private JavaMailSender mailSender;
    @Mock private SpringTemplateEngine templateEngine;
    @Mock private Auditor auditor;

    private MailNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier =
                new MailNotifier(
                        mailSender,
                        templateEngine,
                        auditor,
                        "no-reply@bloomtrade.local",
                        "http://localhost:5173");
    }

    @Test
    void shouldSendWelcomeEmailAndNotAuditWhenSmtpSucceeds() {
        givenMailSucceeds("email/welcome");

        notifier.sendWelcomeEmail(WELCOME_CMD);

        verify(mailSender).send(any(MimeMessage.class));
        verify(auditor, never()).record(any());
    }

    @Test
    void shouldSendOtpEmailAndNotAuditWhenSmtpSucceeds() {
        givenMailSucceeds("email/otp");

        notifier.sendOtpEmail(OTP_CMD);

        verify(mailSender).send(any(MimeMessage.class));
        verify(auditor, never()).record(any());
    }

    @Test
    void shouldSendAccountLockedEmailAndNotAuditWhenSmtpSucceeds() {
        givenMailSucceeds("email/account-locked");

        notifier.sendAccountLockedEmail(LOCKED_CMD);

        verify(mailSender).send(any(MimeMessage.class));
        verify(auditor, never()).record(any());
    }

    @Test
    void shouldAuditWelcomeEmailFailedWhenSmtpThrows() {
        givenMailFails("email/welcome");

        notifier.sendWelcomeEmail(WELCOME_CMD);

        AuditEvent ev = capturedAuditEvent();
        assertThat(ev.eventType()).isEqualTo(AuditEventType.WELCOME_EMAIL_FAILED);
        assertThat(ev.resource()).isEqualTo("welcome-email");
        assertThat(ev.details())
                .containsEntry("userId", "uuid-123")
                .containsEntry("emailProvider", "mailhog");
        assertThat(ev.details().get("errorMessage")).isNotNull();
    }

    @Test
    void shouldAuditOtpEmailFailedWhenSmtpThrows() {
        givenMailFails("email/otp");

        notifier.sendOtpEmail(OTP_CMD);

        AuditEvent ev = capturedAuditEvent();
        assertThat(ev.eventType()).isEqualTo(AuditEventType.OTP_EMAIL_FAILED);
        assertThat(ev.resource()).isEqualTo("otp-email");
        assertThat(ev.details()).containsEntry("userId", "uuid-123");
    }

    @Test
    void shouldAuditAccountLockedEmailFailedWhenSmtpThrows() {
        givenMailFails("email/account-locked");

        notifier.sendAccountLockedEmail(LOCKED_CMD);

        AuditEvent ev = capturedAuditEvent();
        assertThat(ev.eventType()).isEqualTo(AuditEventType.ACCOUNT_LOCKED_EMAIL_FAILED);
        assertThat(ev.resource()).isEqualTo("account-locked-email");
        assertThat(ev.details()).containsEntry("userId", "uuid-123");
    }

    private void givenMailSucceeds(String template) {
        when(templateEngine.process(eq(template), any(Context.class)))
                .thenReturn("<html>hola</html>");
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
    }

    private void givenMailFails(String template) {
        when(templateEngine.process(eq(template), any(Context.class))).thenReturn("<html/>");
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(MimeMessage.class));
    }

    private AuditEvent capturedAuditEvent() {
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditor).record(captor.capture());
        return captor.getValue();
    }
}
