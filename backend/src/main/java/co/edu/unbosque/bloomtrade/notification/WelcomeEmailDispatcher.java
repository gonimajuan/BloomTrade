package co.edu.unbosque.bloomtrade.notification;

import co.edu.unbosque.bloomtrade.audit.AuditEvent;
import co.edu.unbosque.bloomtrade.audit.AuditEvent.AuditResult;
import co.edu.unbosque.bloomtrade.audit.AuditEventType;
import co.edu.unbosque.bloomtrade.audit.Auditor;
import co.edu.unbosque.bloomtrade.notification.dto.WelcomeEmailCommand;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

/**
 * Implementación de {@link Notifier} (componente {@code WelcomeEmailDispatcher} de spec HU-F01
 * §8.1). Render Thymeleaf + envío SMTP vía Spring Mail hacia MailHog. Asíncrono: el registro ya
 * respondió 201 (spec §5.3.6); si MailHog falla se emite {@code WELCOME_EMAIL_FAILED} (warning) y
 * el registro permanece exitoso.
 */
@Component
public class WelcomeEmailDispatcher implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(WelcomeEmailDispatcher.class);
    private static final String SUBJECT = "Bienvenido a BloomTrade";
    private static final String RESOURCE = "welcome-email";

    private final JavaMailSender mailSender;
    private final SpringTemplateEngine templateEngine;
    private final Auditor auditor;
    private final String from;
    private final String loginUrl;

    public WelcomeEmailDispatcher(
            JavaMailSender mailSender,
            SpringTemplateEngine templateEngine,
            Auditor auditor,
            @Value("${MAIL_FROM:no-reply@bloomtrade.local}") String from,
            @Value("${APP_BASE_URL:http://localhost:5173}") String appBaseUrl) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.auditor = auditor;
        this.from = from;
        this.loginUrl = appBaseUrl + "/login";
    }

    @Override
    @Async("notificationExecutor")
    public void sendWelcomeEmail(WelcomeEmailCommand command) {
        try {
            Context ctx = new Context();
            ctx.setVariable("nombreCompleto", command.nombreCompleto());
            ctx.setVariable("loginUrl", loginUrl);
            String html = templateEngine.process("email/welcome", ctx);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(command.toEmail());
            helper.setSubject(SUBJECT);
            helper.setText(html, true);
            mailSender.send(message);
            log.info("Email de bienvenida enviado a {}", command.toEmail());
        } catch (MailException | MessagingException e) {
            log.warn("No se pudo enviar el email de bienvenida a {}", command.toEmail(), e);
            auditor.record(
                    AuditEvent.builder()
                            .eventType(AuditEventType.WELCOME_EMAIL_FAILED)
                            .resource(RESOURCE)
                            .result(AuditResult.DENIED)
                            .actorId(command.userId())
                            .detail("userId", command.userId())
                            .detail("emailProvider", "mailhog")
                            .detail("errorMessage", e.getMessage())
                            .build());
        }
    }
}
