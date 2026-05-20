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
import co.edu.unbosque.bloomtrade.notification.WelcomeEmailDispatcher;
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
class WelcomeEmailDispatcherTest {

    private static final WelcomeEmailCommand CMD =
            new WelcomeEmailCommand("uuid-123", "juan@example.com", "Juan Pérez García");

    @Mock private JavaMailSender mailSender;
    @Mock private SpringTemplateEngine templateEngine;
    @Mock private Auditor auditor;

    private WelcomeEmailDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher =
                new WelcomeEmailDispatcher(
                        mailSender,
                        templateEngine,
                        auditor,
                        "no-reply@bloomtrade.local",
                        "http://localhost:5173");
    }

    @Test
    void shouldSendEmailAndNotAuditWhenSmtpSucceeds() {
        when(templateEngine.process(eq("email/welcome"), any(Context.class)))
                .thenReturn("<html>hola</html>");
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));

        dispatcher.sendWelcomeEmail(CMD);

        verify(mailSender).send(any(MimeMessage.class));
        verify(auditor, never()).record(any());
    }

    @Test
    void shouldAuditWelcomeEmailFailedWhenSmtpThrows() {
        when(templateEngine.process(eq("email/welcome"), any(Context.class)))
                .thenReturn("<html/>");
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(MimeMessage.class));

        // No debe re-lanzar: el registro ya respondió 201 (spec §5.3.6).
        dispatcher.sendWelcomeEmail(CMD);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditor).record(captor.capture());
        AuditEvent ev = captor.getValue();
        assertThat(ev.eventType()).isEqualTo(AuditEventType.WELCOME_EMAIL_FAILED);
        assertThat(ev.details())
                .containsEntry("userId", "uuid-123")
                .containsEntry("emailProvider", "mailhog");
        assertThat(ev.details().get("errorMessage")).isNotNull();
    }
}
