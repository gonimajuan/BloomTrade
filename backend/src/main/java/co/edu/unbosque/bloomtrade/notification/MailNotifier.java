package co.edu.unbosque.bloomtrade.notification;

import co.edu.unbosque.bloomtrade.audit.AuditEvent;
import co.edu.unbosque.bloomtrade.audit.AuditEvent.AuditResult;
import co.edu.unbosque.bloomtrade.audit.AuditEventType;
import co.edu.unbosque.bloomtrade.audit.Auditor;
import co.edu.unbosque.bloomtrade.notification.dto.AccountLockedEmailCommand;
import co.edu.unbosque.bloomtrade.notification.dto.CancellationScheduledEmailCommand;
import co.edu.unbosque.bloomtrade.notification.dto.OrderExecutedEmailCommand;
import co.edu.unbosque.bloomtrade.notification.dto.OrderFailedEmailCommand;
import co.edu.unbosque.bloomtrade.notification.dto.OrderQueuedEmailCommand;
import co.edu.unbosque.bloomtrade.notification.dto.OrderRejectedEmailCommand;
import co.edu.unbosque.bloomtrade.notification.dto.OtpEmailCommand;
import co.edu.unbosque.bloomtrade.notification.dto.SubscriptionExpiredEmailCommand;
import co.edu.unbosque.bloomtrade.notification.dto.SubscriptionPaymentFailedEmailCommand;
import co.edu.unbosque.bloomtrade.notification.dto.WelcomeEmailCommand;
import co.edu.unbosque.bloomtrade.notification.dto.WelcomePremiumEmailCommand;
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
 *
 * <p>HU-F10 Lote C: 8 métodos de orden (4 BUY + 4 SELL) con templates separadas por side y
 * subjects específicos. Reutiliza los mismos {@link AuditEventType} F09 para fallos de email —
 * el side se distingue en el {@code resource} string ({@code order-executed-buy-email} vs
 * {@code order-executed-sell-email}).
 */
@Component
public class MailNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(MailNotifier.class);
    private static final String PROVIDER = "mailhog";
    private static final String WELCOME_SUBJECT = "Bienvenido a BloomTrade";
    private static final String OTP_SUBJECT = "Tu código de acceso a BloomTrade";
    private static final String ACCOUNT_LOCKED_SUBJECT = "Cuenta bloqueada temporalmente";
    private static final String WELCOME_PREMIUM_SUBJECT = "¡Bienvenido a BloomTrade Premium!";
    private static final String CANCEL_SCHEDULED_SUBJECT = "Tu suscripción premium se cancelará pronto";
    private static final String EXPIRED_SUBJECT = "Tu suscripción premium ha terminado";
    private static final String PAYMENT_FAILED_SUBJECT = "Tu pago de renovación falló";
    private static final String ORDER_EXECUTED_BUY_SUBJECT = "Tu orden de compra fue ejecutada";
    private static final String ORDER_EXECUTED_SELL_SUBJECT = "Tu orden de venta fue ejecutada";
    private static final String ORDER_REJECTED_BUY_SUBJECT = "Tu orden de compra fue rechazada";
    private static final String ORDER_REJECTED_SELL_SUBJECT = "Tu orden de venta fue rechazada";
    private static final String ORDER_FAILED_BUY_SUBJECT = "Tu orden de compra no pudo procesarse";
    private static final String ORDER_FAILED_SELL_SUBJECT = "Tu orden de venta no pudo procesarse";
    private static final String ORDER_QUEUED_BUY_SUBJECT = "Tu orden de compra quedó en cola";
    private static final String ORDER_QUEUED_SELL_SUBJECT = "Tu orden de venta quedó en cola";

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

    @Override
    @Async("notificationExecutor")
    public void sendWelcomePremiumEmail(WelcomePremiumEmailCommand command) {
        Context ctx = new Context();
        ctx.setVariable("nombreCompleto", command.nombreCompleto());
        ctx.setVariable("plan", command.plan().name());
        ctx.setVariable("currentPeriodEnd", command.currentPeriodEnd());
        ctx.setVariable("loginUrl", loginUrl);
        send(
                command.userId(),
                command.toEmail(),
                WELCOME_PREMIUM_SUBJECT,
                "email/welcome-premium",
                ctx,
                AuditEventType.WELCOME_PREMIUM_EMAIL_FAILED,
                "welcome-premium-email");
    }

    @Override
    @Async("notificationExecutor")
    public void sendCancellationScheduledEmail(CancellationScheduledEmailCommand command) {
        Context ctx = new Context();
        ctx.setVariable("nombreCompleto", command.nombreCompleto());
        ctx.setVariable("currentPeriodEnd", command.currentPeriodEnd());
        ctx.setVariable("loginUrl", loginUrl);
        send(
                command.userId(),
                command.toEmail(),
                CANCEL_SCHEDULED_SUBJECT,
                "email/subscription-scheduled-to-cancel",
                ctx,
                AuditEventType.CANCELLATION_SCHEDULED_EMAIL_FAILED,
                "cancellation-scheduled-email");
    }

    @Override
    @Async("notificationExecutor")
    public void sendSubscriptionExpiredEmail(SubscriptionExpiredEmailCommand command) {
        Context ctx = new Context();
        ctx.setVariable("nombreCompleto", command.nombreCompleto());
        ctx.setVariable("plan", command.plan().name());
        ctx.setVariable("loginUrl", loginUrl);
        send(
                command.userId(),
                command.toEmail(),
                EXPIRED_SUBJECT,
                "email/subscription-expired",
                ctx,
                AuditEventType.SUBSCRIPTION_EXPIRED_EMAIL_FAILED,
                "subscription-expired-email");
    }

    @Override
    @Async("notificationExecutor")
    public void sendSubscriptionPaymentFailedEmail(SubscriptionPaymentFailedEmailCommand command) {
        Context ctx = new Context();
        ctx.setVariable("nombreCompleto", command.nombreCompleto());
        ctx.setVariable("plan", command.plan().name());
        ctx.setVariable("loginUrl", loginUrl);
        send(
                command.userId(),
                command.toEmail(),
                PAYMENT_FAILED_SUBJECT,
                "email/subscription-payment-failed",
                ctx,
                AuditEventType.SUBSCRIPTION_PAYMENT_FAILED_EMAIL_FAILED,
                "subscription-payment-failed-email");
    }

    // ─── HU-F09 + HU-F10 — Órdenes Market BUY/SELL ──────────────────────────

    @Override
    @Async("notificationExecutor")
    public void sendOrderExecutedEmailBuy(OrderExecutedEmailCommand command) {
        Context ctx = new Context();
        ctx.setVariable("nombreCompleto", command.nombreCompleto());
        ctx.setVariable("ticker", command.ticker());
        ctx.setVariable("quantity", command.quantity());
        ctx.setVariable("executionUnitPrice", command.executionUnitPrice().toPlainString());
        ctx.setVariable("executionTotal", command.executionTotal().toPlainString());
        ctx.setVariable("commission", command.commission().toPlainString());
        ctx.setVariable("newBalance", command.newBalance().toPlainString());
        ctx.setVariable("loginUrl", loginUrl);
        send(
                command.userId(),
                command.toEmail(),
                ORDER_EXECUTED_BUY_SUBJECT,
                "email/order-executed-buy",
                ctx,
                AuditEventType.ORDER_EXECUTED_EMAIL_FAILED,
                "order-executed-buy-email");
    }

    @Override
    @Async("notificationExecutor")
    public void sendOrderExecutedEmailSell(OrderExecutedEmailCommand command) {
        Context ctx = new Context();
        ctx.setVariable("nombreCompleto", command.nombreCompleto());
        ctx.setVariable("ticker", command.ticker());
        ctx.setVariable("quantity", command.quantity());
        ctx.setVariable("executionUnitPrice", command.executionUnitPrice().toPlainString());
        ctx.setVariable("executionTotal", command.executionTotal().toPlainString());
        ctx.setVariable("commission", command.commission().toPlainString());
        ctx.setVariable("newBalance", command.newBalance().toPlainString());
        // SELL-only — el template usa th:if/th:unless sobre estos.
        ctx.setVariable("positionResultingQty", command.positionResultingQty());
        ctx.setVariable(
                "positionDeleted",
                command.positionDeleted() != null && command.positionDeleted());
        ctx.setVariable("loginUrl", loginUrl);
        send(
                command.userId(),
                command.toEmail(),
                ORDER_EXECUTED_SELL_SUBJECT,
                "email/order-executed-sell",
                ctx,
                AuditEventType.ORDER_EXECUTED_EMAIL_FAILED,
                "order-executed-sell-email");
    }

    @Override
    @Async("notificationExecutor")
    public void sendOrderRejectedEmailBuy(OrderRejectedEmailCommand command) {
        Context ctx = new Context();
        ctx.setVariable("nombreCompleto", command.nombreCompleto());
        ctx.setVariable("ticker", command.ticker());
        ctx.setVariable("quantity", command.quantity());
        ctx.setVariable("alpacaReason", command.alpacaReason());
        ctx.setVariable("loginUrl", loginUrl);
        send(
                command.userId(),
                command.toEmail(),
                ORDER_REJECTED_BUY_SUBJECT,
                "email/order-rejected-buy",
                ctx,
                AuditEventType.ORDER_REJECTED_EMAIL_FAILED,
                "order-rejected-buy-email");
    }

    @Override
    @Async("notificationExecutor")
    public void sendOrderRejectedEmailSell(OrderRejectedEmailCommand command) {
        Context ctx = new Context();
        ctx.setVariable("nombreCompleto", command.nombreCompleto());
        ctx.setVariable("ticker", command.ticker());
        ctx.setVariable("quantity", command.quantity());
        ctx.setVariable("alpacaReason", command.alpacaReason());
        ctx.setVariable("loginUrl", loginUrl);
        send(
                command.userId(),
                command.toEmail(),
                ORDER_REJECTED_SELL_SUBJECT,
                "email/order-rejected-sell",
                ctx,
                AuditEventType.ORDER_REJECTED_EMAIL_FAILED,
                "order-rejected-sell-email");
    }

    @Override
    @Async("notificationExecutor")
    public void sendOrderFailedEmailBuy(OrderFailedEmailCommand command) {
        Context ctx = new Context();
        ctx.setVariable("nombreCompleto", command.nombreCompleto());
        ctx.setVariable("ticker", command.ticker());
        ctx.setVariable("quantity", command.quantity());
        ctx.setVariable("errorMessage", command.errorMessage());
        ctx.setVariable("loginUrl", loginUrl);
        send(
                command.userId(),
                command.toEmail(),
                ORDER_FAILED_BUY_SUBJECT,
                "email/order-failed-buy",
                ctx,
                AuditEventType.ORDER_FAILED_EMAIL_FAILED,
                "order-failed-buy-email");
    }

    @Override
    @Async("notificationExecutor")
    public void sendOrderFailedEmailSell(OrderFailedEmailCommand command) {
        Context ctx = new Context();
        ctx.setVariable("nombreCompleto", command.nombreCompleto());
        ctx.setVariable("ticker", command.ticker());
        ctx.setVariable("quantity", command.quantity());
        ctx.setVariable("errorMessage", command.errorMessage());
        ctx.setVariable("loginUrl", loginUrl);
        send(
                command.userId(),
                command.toEmail(),
                ORDER_FAILED_SELL_SUBJECT,
                "email/order-failed-sell",
                ctx,
                AuditEventType.ORDER_FAILED_EMAIL_FAILED,
                "order-failed-sell-email");
    }

    @Override
    @Async("notificationExecutor")
    public void sendOrderQueuedEmailBuy(OrderQueuedEmailCommand command) {
        Context ctx = new Context();
        ctx.setVariable("nombreCompleto", command.nombreCompleto());
        ctx.setVariable("ticker", command.ticker());
        ctx.setVariable("quantity", command.quantity());
        ctx.setVariable("quotedUnitPrice", command.quotedUnitPrice().toPlainString());
        ctx.setVariable("quotedTotal", command.quotedTotal().toPlainString());
        ctx.setVariable("commission", command.commission().toPlainString());
        ctx.setVariable("newBalance", command.newBalance().toPlainString());
        ctx.setVariable("alpacaOrderId", command.alpacaOrderId());
        ctx.setVariable("loginUrl", loginUrl);
        send(
                command.userId(),
                command.toEmail(),
                ORDER_QUEUED_BUY_SUBJECT,
                "email/order-queued-buy",
                ctx,
                AuditEventType.ORDER_QUEUED_EMAIL_FAILED,
                "order-queued-buy-email");
    }

    @Override
    @Async("notificationExecutor")
    public void sendOrderQueuedEmailSell(OrderQueuedEmailCommand command) {
        Context ctx = new Context();
        ctx.setVariable("nombreCompleto", command.nombreCompleto());
        ctx.setVariable("ticker", command.ticker());
        ctx.setVariable("quantity", command.quantity());
        ctx.setVariable("quotedUnitPrice", command.quotedUnitPrice().toPlainString());
        ctx.setVariable("quotedTotal", command.quotedTotal().toPlainString());
        ctx.setVariable("commission", command.commission().toPlainString());
        ctx.setVariable("newBalance", command.newBalance().toPlainString());
        ctx.setVariable("alpacaOrderId", command.alpacaOrderId());
        ctx.setVariable("positionResultingQty", command.positionResultingQty());
        ctx.setVariable("loginUrl", loginUrl);
        send(
                command.userId(),
                command.toEmail(),
                ORDER_QUEUED_SELL_SUBJECT,
                "email/order-queued-sell",
                ctx,
                AuditEventType.ORDER_QUEUED_EMAIL_FAILED,
                "order-queued-sell-email");
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
