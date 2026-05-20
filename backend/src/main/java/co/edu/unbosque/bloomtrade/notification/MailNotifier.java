package co.edu.unbosque.bloomtrade.notification;

import co.edu.unbosque.bloomtrade.audit.AuditEvent;
import co.edu.unbosque.bloomtrade.audit.AuditEvent.AuditResult;
import co.edu.unbosque.bloomtrade.audit.AuditEventType;
import co.edu.unbosque.bloomtrade.audit.Auditor;
import co.edu.unbosque.bloomtrade.notification.dto.AccountLockedEmailCommand;
import co.edu.unbosque.bloomtrade.notification.dto.OtpEmailCommand;
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
 * Implementación SMTP de {@link Notifier}. Renderiza plantillas Thymeleaf y envía vía Spring Mail
 * hacia MailHog en dev/test. Los envíos son asíncronos: si SMTP falla, el flujo principal ya
 * confirmado no se revierte; se emite un evento de auditoría de fallo.
 */
@Component
public class MailNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(MailNotifier.class);
    private static final String PROVIDER = "mailhog";
    private static final String WELCOME_SUBJECT = "Bienvenido a BloomTrade";
    private static final String OTP_SUBJECT = "Tu código de acceso a BloomTrade";
    private static final String ACCOUNT_LOCKED_SUBJECT = "Cuenta bloqueada temporalmente";

    private final JavaMailSender mailSender;
    private final SpringTemplateEngine templateEngine;
    private final Auditor auditor;
    private final String from;
    private final String loginUrl;

    public MailNotifier(
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
        Context ctx = new Context();
        ctx.setVariable("nombreCompleto", command.nombreCompleto());
        ctx.setVariable("loginUrl", loginUrl);
        send(
                command.userId(),
                command.toEmail(),
                WELCOME_SUBJECT,
                "email/welcome",
                ctx,
                AuditEventType.WELCOME_EMAIL_FAILED,
                "welcome-email");
    }

    @Override
    @Async("notificationExecutor")
    public void sendOtpEmail(OtpEmailCommand command) {
        Context ctx = new Context();
        ctx.setVariable("nombreCompleto", command.nombreCompleto());
        ctx.setVariable("otpCode", command.otpCode());
        ctx.setVariable("expiresInMinutes", command.expiresInMinutes());
        send(
                command.userId(),
                command.toEmail(),
                OTP_SUBJECT,
                "email/otp",
                ctx,
                AuditEventType.OTP_EMAIL_FAILED,
                "otp-email");
    }

    @Override
    @Async("notificationExecutor")
    public void sendAccountLockedEmail(AccountLockedEmailCommand command) {
        Context ctx = new Context();
        ctx.setVariable("nombreCompleto", command.nombreCompleto());
        ctx.setVariable("lockDurationMinutes", command.lockDurationMinutes());
        ctx.setVariable("loginUrl", loginUrl);
        send(
                command.userId(),
                command.toEmail(),
                ACCOUNT_LOCKED_SUBJECT,
                "email/account-locked",
                ctx,
                AuditEventType.ACCOUNT_LOCKED_EMAIL_FAILED,
                "account-locked-email");
    }

    private void send(
            String userId,
            String toEmail,
            String subject,
            String template,
            Context ctx,
            AuditEventType failureEventType,
            String resource) {
        try {
            String html = templateEngine.process(template, ctx);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);
            log.info("Email '{}' enviado a {}", subject, toEmail);
        } catch (MailException | MessagingException e) {
            log.warn("No se pudo enviar el email '{}' a {}", subject, toEmail, e);
            auditor.record(
                    AuditEvent.builder()
                            .eventType(failureEventType)
                            .resource(resource)
                            .result(AuditResult.DENIED)
                            .actorId(userId)
                            .detail("userId", userId)
                            .detail("emailProvider", PROVIDER)
                            .detail("errorMessage", e.getMessage())
                            .build());
        }
    }
}
