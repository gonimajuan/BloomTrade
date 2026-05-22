package co.edu.unbosque.bloomtrade.auth.subscription.service;

import co.edu.unbosque.bloomtrade.audit.AuditEvent;
import co.edu.unbosque.bloomtrade.audit.AuditEvent.AuditResult;
import co.edu.unbosque.bloomtrade.audit.AuditEventType;
import co.edu.unbosque.bloomtrade.audit.Auditor;
import co.edu.unbosque.bloomtrade.auth.domain.User;
import co.edu.unbosque.bloomtrade.auth.repository.UserRepository;
import co.edu.unbosque.bloomtrade.auth.subscription.domain.BillingPlan;
import co.edu.unbosque.bloomtrade.auth.subscription.domain.StripeWebhookEvent;
import co.edu.unbosque.bloomtrade.auth.subscription.domain.Subscription;
import co.edu.unbosque.bloomtrade.auth.subscription.exception.WebhookSignatureInvalidException;
import co.edu.unbosque.bloomtrade.auth.subscription.repository.StripeWebhookEventRepository;
import co.edu.unbosque.bloomtrade.auth.subscription.repository.SubscriptionRepository;
import co.edu.unbosque.bloomtrade.integration.stripe.StripeAdapter;
import co.edu.unbosque.bloomtrade.notification.Notifier;
import co.edu.unbosque.bloomtrade.notification.dto.CancellationScheduledEmailCommand;
import co.edu.unbosque.bloomtrade.notification.dto.SubscriptionExpiredEmailCommand;
import co.edu.unbosque.bloomtrade.notification.dto.SubscriptionPaymentFailedEmailCommand;
import co.edu.unbosque.bloomtrade.notification.dto.WelcomePremiumEmailCommand;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.Invoice;
import com.stripe.model.StripeObject;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Procesa webhooks de Stripe con signature verification + idempotencia (HU-F06 §5.1 paso 3,
 * §5.2.2-§5.2.5). Cuatro tipos de evento manejados; cualquier otro tipo se ignora con audit.
 *
 * <p><strong>Idempotencia</strong> (D6 plan): el INSERT en {@code stripe_webhook_events} con
 * {@code UNIQUE} sobre {@code stripe_event_id} es la fuente de verdad. Si el mismo evento llega
 * dos veces, el segundo INSERT viola el constraint → captura → 200 {@code STRIPE_WEBHOOK_DUPLICATE}
 * sin reprocesar.
 *
 * <p><strong>Transacción</strong>: todo el procesamiento ocurre en una sola transacción. Si el
 * negocio falla, el rollback borra también el registro de {@code stripe_webhook_events}; Stripe
 * reintentará y la idempotencia operará en el próximo intento.
 */
@Service
public class StripeWebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookHandler.class);
    private static final String RESOURCE = "/api/v1/webhooks/stripe";

    private final StripeAdapter stripeAdapter;
    private final SubscriptionRepository subscriptionRepository;
    private final StripeWebhookEventRepository webhookEventRepository;
    private final UserRepository userRepository;
    private final Notifier notifier;
    private final Auditor auditor;

    public StripeWebhookHandler(
            StripeAdapter stripeAdapter,
            SubscriptionRepository subscriptionRepository,
            StripeWebhookEventRepository webhookEventRepository,
            UserRepository userRepository,
            Notifier notifier,
            Auditor auditor) {
        this.stripeAdapter = stripeAdapter;
        this.subscriptionRepository = subscriptionRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.userRepository = userRepository;
        this.notifier = notifier;
        this.auditor = auditor;
    }

    /** Punto de entrada — invocado por {@code StripeWebhookController}. */
    @Transactional
    public void handle(String rawPayload, String sigHeader, String ipOrigin) {
        Event event;
        try {
            event = stripeAdapter.constructWebhookEvent(rawPayload, sigHeader);
        } catch (SignatureVerificationException e) {
            auditor.record(
                    AuditEvent.builder()
                            .eventType(AuditEventType.STRIPE_WEBHOOK_SIGNATURE_FAILED)
                            .resource(RESOURCE)
                            .result(AuditResult.DENIED)
                            .ipOrigin(ipOrigin)
                            .detail("reason", "INVALID_SIGNATURE")
                            .build());
            throw new WebhookSignatureInvalidException(e);
        }

        // Idempotencia: insertamos primero; si el UNIQUE falla, es duplicado.
        StripeWebhookEvent record;
        try {
            record =
                    webhookEventRepository.saveAndFlush(
                            StripeWebhookEvent.received(
                                    event.getId(), event.getType(), rawPayload));
        } catch (DataIntegrityViolationException dup) {
            auditor.record(
                    AuditEvent.builder()
                            .eventType(AuditEventType.STRIPE_WEBHOOK_DUPLICATE)
                            .resource(RESOURCE)
                            .result(AuditResult.ALLOWED)
                            .ipOrigin(ipOrigin)
                            .detail("stripeEventId", event.getId())
                            .detail("eventType", event.getType())
                            .build());
            log.info("Webhook duplicado ignorado: {} ({})", event.getId(), event.getType());
            return;
        }

        auditor.record(
                AuditEvent.builder()
                        .eventType(AuditEventType.STRIPE_WEBHOOK_RECEIVED)
                        .resource(RESOURCE)
                        .result(AuditResult.ALLOWED)
                        .ipOrigin(ipOrigin)
                        .detail("stripeEventId", event.getId())
                        .detail("eventType", event.getType())
                        .build());

        try {
            switch (event.getType()) {
                case "checkout.session.completed" -> handleCheckoutCompleted(event, ipOrigin);
                case "customer.subscription.updated" -> handleSubscriptionUpdated(event, ipOrigin);
                case "customer.subscription.deleted" -> handleSubscriptionDeleted(event, ipOrigin);
                case "invoice.payment_failed" -> handleInvoicePaymentFailed(event, ipOrigin);
                default -> log.info("Webhook type {} no manejado (ignorado)", event.getType());
            }
            record.markAsProcessed();
        } catch (RuntimeException e) {
            log.error(
                    "Error procesando webhook {} ({}): {}",
                    event.getId(),
                    event.getType(),
                    e.getMessage(),
                    e);
            auditor.record(
                    AuditEvent.builder()
                            .eventType(AuditEventType.STRIPE_WEBHOOK_PROCESSING_FAILED)
                            .resource(RESOURCE)
                            .result(AuditResult.DENIED)
                            .ipOrigin(ipOrigin)
                            .detail("stripeEventId", event.getId())
                            .detail("eventType", event.getType())
                            .detail("errorClass", e.getClass().getName())
                            .build());
            throw e; // rollback → Stripe reintentará
        }
    }

    private void handleCheckoutCompleted(Event event, String ipOrigin) {
        com.stripe.model.checkout.Session session =
                (com.stripe.model.checkout.Session) extractObject(event);
        String subscriptionId = session.getSubscription();
        String customerId = session.getCustomer();
        UUID userId = UUID.fromString(session.getMetadata().get("userId"));
        BillingPlan plan = BillingPlan.valueOf(session.getMetadata().get("plan"));

        com.stripe.model.Subscription stripeSub = stripeAdapter.retrieveSubscription(subscriptionId);

        Subscription sub =
                Subscription.activate(
                        userId,
                        customerId,
                        subscriptionId,
                        plan,
                        Instant.ofEpochSecond(stripeSub.getCurrentPeriodStart()),
                        Instant.ofEpochSecond(stripeSub.getCurrentPeriodEnd()));
        subscriptionRepository.save(sub);

        auditor.record(
                AuditEvent.builder()
                        .eventType(AuditEventType.SUBSCRIPTION_ACTIVATED)
                        .resource(RESOURCE)
                        .result(AuditResult.ALLOWED)
                        .actorId(userId.toString())
                        .ipOrigin(ipOrigin)
                        .detail("subscriptionId", sub.getId().toString())
                        .detail("plan", plan.name())
                        .detail("periodEnd", sub.getCurrentPeriodEnd().toString())
                        .detail("stripeSubscriptionId", subscriptionId)
                        .build());

        // Email de bienvenida.
        userRepository
                .findById(userId)
                .ifPresent(
                        user ->
                                notifier.sendWelcomePremiumEmail(
                                        new WelcomePremiumEmailCommand(
                                                userId.toString(),
                                                user.getEmail(),
                                                user.getNombreCompleto(),
                                                plan,
                                                sub.getCurrentPeriodEnd())));
    }

    private void handleSubscriptionUpdated(Event event, String ipOrigin) {
        com.stripe.model.Subscription stripeSub =
                (com.stripe.model.Subscription) extractObject(event);
        Optional<Subscription> opt =
                subscriptionRepository.findByStripeSubscriptionId(stripeSub.getId());
        if (opt.isEmpty()) {
            emitOrphan(event, ipOrigin, stripeSub.getId());
            return;
        }
        Subscription sub = opt.get();
        boolean wasCancelScheduled = sub.isCancelAtPeriodEnd();
        boolean isCancelScheduled = Boolean.TRUE.equals(stripeSub.getCancelAtPeriodEnd());
        Instant newPeriodEnd = Instant.ofEpochSecond(stripeSub.getCurrentPeriodEnd());

        sub.syncPeriod(Instant.ofEpochSecond(stripeSub.getCurrentPeriodStart()), newPeriodEnd);

        if (!wasCancelScheduled && isCancelScheduled) {
            sub.scheduleCancellation(newPeriodEnd);
            auditor.record(
                    AuditEvent.builder()
                            .eventType(AuditEventType.SUBSCRIPTION_CANCELLED_SCHEDULED)
                            .resource(RESOURCE)
                            .result(AuditResult.ALLOWED)
                            .actorId(sub.getUserId().toString())
                            .ipOrigin(ipOrigin)
                            .detail("subscriptionId", sub.getId().toString())
                            .detail("periodEnd", newPeriodEnd.toString())
                            .build());
            userRepository
                    .findById(sub.getUserId())
                    .ifPresent(
                            user ->
                                    notifier.sendCancellationScheduledEmail(
                                            new CancellationScheduledEmailCommand(
                                                    sub.getUserId().toString(),
                                                    user.getEmail(),
                                                    user.getNombreCompleto(),
                                                    newPeriodEnd)));
        } else if (wasCancelScheduled && !isCancelScheduled) {
            sub.reactivate();
            auditor.record(
                    AuditEvent.builder()
                            .eventType(AuditEventType.SUBSCRIPTION_REACTIVATED)
                            .resource(RESOURCE)
                            .result(AuditResult.ALLOWED)
                            .actorId(sub.getUserId().toString())
                            .ipOrigin(ipOrigin)
                            .detail("subscriptionId", sub.getId().toString())
                            .detail("periodEnd", newPeriodEnd.toString())
                            .build());
        }
    }

    private void handleSubscriptionDeleted(Event event, String ipOrigin) {
        com.stripe.model.Subscription stripeSub =
                (com.stripe.model.Subscription) extractObject(event);
        Optional<Subscription> opt =
                subscriptionRepository.findByStripeSubscriptionId(stripeSub.getId());
        if (opt.isEmpty()) {
            emitOrphan(event, ipOrigin, stripeSub.getId());
            return;
        }
        Subscription sub = opt.get();
        sub.markAsCancelled();

        auditor.record(
                AuditEvent.builder()
                        .eventType(AuditEventType.SUBSCRIPTION_TERMINATED)
                        .resource(RESOURCE)
                        .result(AuditResult.ALLOWED)
                        .actorId(sub.getUserId().toString())
                        .ipOrigin(ipOrigin)
                        .detail("subscriptionId", sub.getId().toString())
                        .detail("reason", "PERIOD_END")
                        .build());

        userRepository
                .findById(sub.getUserId())
                .ifPresent(
                        user ->
                                notifier.sendSubscriptionExpiredEmail(
                                        new SubscriptionExpiredEmailCommand(
                                                sub.getUserId().toString(),
                                                user.getEmail(),
                                                user.getNombreCompleto(),
                                                sub.getPlan())));
    }

    private void handleInvoicePaymentFailed(Event event, String ipOrigin) {
        Invoice invoice = (Invoice) extractObject(event);
        String stripeSubscriptionId = invoice.getSubscription();
        Optional<Subscription> opt =
                subscriptionRepository.findByStripeSubscriptionId(stripeSubscriptionId);
        if (opt.isEmpty()) {
            emitOrphan(event, ipOrigin, stripeSubscriptionId);
            return;
        }
        Subscription sub = opt.get();
        sub.markAsPastDue();

        auditor.record(
                AuditEvent.builder()
                        .eventType(AuditEventType.SUBSCRIPTION_PAYMENT_FAILED)
                        .resource(RESOURCE)
                        .result(AuditResult.ALLOWED)
                        .actorId(sub.getUserId().toString())
                        .ipOrigin(ipOrigin)
                        .detail("subscriptionId", sub.getId().toString())
                        .detail("stripeInvoiceId", invoice.getId())
                        .build());

        userRepository
                .findById(sub.getUserId())
                .ifPresent(
                        user ->
                                notifier.sendSubscriptionPaymentFailedEmail(
                                        new SubscriptionPaymentFailedEmailCommand(
                                                sub.getUserId().toString(),
                                                user.getEmail(),
                                                user.getNombreCompleto(),
                                                sub.getPlan())));
    }

    private void emitOrphan(Event event, String ipOrigin, String stripeSubscriptionId) {
        log.warn(
                "Webhook orphan: {} refiere subscription {} no presente en BD",
                event.getType(),
                stripeSubscriptionId);
        auditor.record(
                AuditEvent.builder()
                        .eventType(AuditEventType.STRIPE_WEBHOOK_ORPHAN)
                        .resource(RESOURCE)
                        .result(AuditResult.ALLOWED)
                        .ipOrigin(ipOrigin)
                        .detail("stripeSubscriptionId", stripeSubscriptionId)
                        .detail("eventType", event.getType())
                        .build());
    }

    /**
     * Extrae el {@link StripeObject} del evento. D22 (descubierto en runtime 2026-05-21): cuando
     * la API version del evento que envía Stripe (p.ej. {@code 2026-04-22.dahlia}) NO coincide
     * con la API version compilada en {@code stripe-java}, {@link EventDataObjectDeserializer
     * #getObject()} retorna {@link java.util.Optional#empty()} y los handlers no pueden continuar.
     *
     * <p>Usamos {@code deserializeUnsafe()} para forzar la deserialización ignorando el mismatch.
     * Es "unsafe" en el sentido de que un campo que Stripe haya renombrado entre versions
     * podría llegar como {@code null} — para el MVP el riesgo es aceptable porque solo accedemos
     * a campos estables (subscription id, customer id, current_period_start/end, metadata).
     * Documentado en {@code stripe-java} README como patrón estándar para handlers de webhook.
     */
    private StripeObject extractObject(Event event) {
        EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
        return deserializer
                .getObject()
                .orElseGet(
                        () -> {
                            try {
                                return deserializer.deserializeUnsafe();
                            } catch (
                                    com.stripe.exception.EventDataObjectDeserializationException
                                            e) {
                                throw new IllegalStateException(
                                        "No se pudo deserializar el objeto del evento "
                                                + event.getType()
                                                + " ni con deserializeUnsafe(). API version del "
                                                + "evento: "
                                                + event.getApiVersion(),
                                        e);
                            }
                        });
    }
}
