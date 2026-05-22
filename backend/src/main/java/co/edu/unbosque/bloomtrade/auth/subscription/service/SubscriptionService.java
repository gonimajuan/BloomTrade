package co.edu.unbosque.bloomtrade.auth.subscription.service;

import co.edu.unbosque.bloomtrade.audit.AuditEvent;
import co.edu.unbosque.bloomtrade.audit.AuditEvent.AuditResult;
import co.edu.unbosque.bloomtrade.audit.AuditEventType;
import co.edu.unbosque.bloomtrade.audit.Auditor;
import co.edu.unbosque.bloomtrade.auth.domain.User;
import co.edu.unbosque.bloomtrade.auth.repository.UserRepository;
import co.edu.unbosque.bloomtrade.auth.subscription.domain.BillingPlan;
import co.edu.unbosque.bloomtrade.auth.subscription.domain.Subscription;
import co.edu.unbosque.bloomtrade.auth.subscription.domain.SubscriptionStatus;
import co.edu.unbosque.bloomtrade.auth.subscription.dto.CheckoutSessionResponse;
import co.edu.unbosque.bloomtrade.auth.subscription.dto.PortalSessionResponse;
import co.edu.unbosque.bloomtrade.auth.subscription.dto.SubscriptionStatusResponse;
import co.edu.unbosque.bloomtrade.auth.subscription.exception.NoStripeCustomerException;
import co.edu.unbosque.bloomtrade.auth.subscription.exception.StripeApiException;
import co.edu.unbosque.bloomtrade.auth.subscription.exception.SubscriptionAlreadyActiveException;
import co.edu.unbosque.bloomtrade.auth.subscription.mapper.SubscriptionMapper;
import co.edu.unbosque.bloomtrade.auth.subscription.repository.SubscriptionRepository;
import co.edu.unbosque.bloomtrade.integration.stripe.StripeAdapter;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orquesta los flujos síncronos del usuario hacia su suscripción premium (HU-F06 §5.1, §5.2.1).
 *
 * <p><strong>Split de transacciones (D8):</strong> {@link #ensureStripeCustomer} corre en
 * {@code REQUIRES_NEW} — si el Customer se crea exitosamente en Stripe pero la transacción
 * principal (createCheckoutSession) falla después, el {@code stripe_customer_id} ya está
 * commiteado en BD. Stripe Customers son reusables; no se acumulan huérfanos.
 *
 * <p>El estado de la suscripción NO se modifica desde aquí (excepto en checkout/portal initial
 * — el resto viene exclusivamente vía webhooks procesados por {@code StripeWebhookHandler}).
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);
    private static final String RESOURCE_CHECKOUT = "/api/v1/subscriptions/checkout-session";
    private static final String RESOURCE_PORTAL = "/api/v1/subscriptions/portal-session";

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionMapper subscriptionMapper;
    private final StripeAdapter stripeAdapter;
    private final Auditor auditor;

    public SubscriptionService(
            UserRepository userRepository,
            SubscriptionRepository subscriptionRepository,
            SubscriptionMapper subscriptionMapper,
            StripeAdapter stripeAdapter,
            Auditor auditor) {
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionMapper = subscriptionMapper;
        this.stripeAdapter = stripeAdapter;
        this.auditor = auditor;
    }

    @Transactional
    public CheckoutSessionResponse createCheckoutSession(UUID userId, BillingPlan plan, String ipOrigin) {
        // 1) Validación: no debe existir suscripción ACTIVE.
        Optional<Subscription> existing =
                subscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE);
        if (existing.isPresent()) {
            throw new SubscriptionAlreadyActiveException(existing.get().getCurrentPeriodEnd());
        }

        // 2) Garantiza Stripe Customer (D8 split — REQUIRES_NEW para que el customer_id quede
        //    persistido aunque el siguiente paso falle).
        String stripeCustomerId = ensureStripeCustomer(userId);

        // 3) Crea Checkout Session vía Stripe.
        try {
            com.stripe.model.checkout.Session session =
                    stripeAdapter.createCheckoutSession(stripeCustomerId, plan, userId);
            auditor.record(
                    AuditEvent.builder()
                            .eventType(AuditEventType.CHECKOUT_SESSION_CREATED)
                            .resource(RESOURCE_CHECKOUT)
                            .result(AuditResult.ALLOWED)
                            .actorId(userId.toString())
                            .ipOrigin(ipOrigin)
                            .detail("plan", plan.name())
                            .detail("stripeSessionId", session.getId())
                            .detail("stripeCustomerId", stripeCustomerId)
                            .build());
            return new CheckoutSessionResponse(session.getUrl(), session.getId());
        } catch (StripeApiException e) {
            auditor.record(
                    AuditEvent.builder()
                            .eventType(AuditEventType.CHECKOUT_SESSION_FAILED)
                            .resource(RESOURCE_CHECKOUT)
                            .result(AuditResult.DENIED)
                            .actorId(userId.toString())
                            .ipOrigin(ipOrigin)
                            .detail("plan", plan.name())
                            .detail("reason", "STRIPE_API_ERROR")
                            .detail("stripeErrorCode", e.getStripeErrorCode())
                            .build());
            throw e;
        }
    }

    /**
     * D8 — corre en su propia transacción. Si la suscripción del checkout falla después, el
     * stripe_customer_id ya está persistido y se reutiliza en el próximo intento (no se crea otro
     * customer en Stripe).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String ensureStripeCustomer(UUID userId) {
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Usuario del JWT no existe en BD: " + userId));
        if (user.getStripeCustomerId() != null) {
            return user.getStripeCustomerId();
        }
        String newCustomerId =
                stripeAdapter.createCustomer(user.getEmail(), user.getNombreCompleto(), userId);
        user.linkStripeCustomer(newCustomerId);
        userRepository.save(user);
        log.info("Linked Stripe customer {} a user {}", newCustomerId, userId);
        return newCustomerId;
    }

    @Transactional(readOnly = true)
    public PortalSessionResponse openBillingPortal(UUID userId, String ipOrigin) {
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(() -> new IllegalStateException("User not found: " + userId));
        if (user.getStripeCustomerId() == null) {
            throw new NoStripeCustomerException();
        }
        com.stripe.model.billingportal.Session session =
                stripeAdapter.createBillingPortalSession(user.getStripeCustomerId());
        auditor.record(
                AuditEvent.builder()
                        .eventType(AuditEventType.BILLING_PORTAL_SESSION_CREATED)
                        .resource(RESOURCE_PORTAL)
                        .result(AuditResult.ALLOWED)
                        .actorId(userId.toString())
                        .ipOrigin(ipOrigin)
                        .detail("stripeCustomerId", user.getStripeCustomerId())
                        .build());
        return new PortalSessionResponse(session.getUrl());
    }

    @Transactional(readOnly = true)
    public SubscriptionStatusResponse getStatus(UUID userId) {
        Optional<Subscription> latest =
                subscriptionRepository.findFirstByUserIdOrderByCreatedAtDesc(userId);
        boolean isPremium =
                latest.map(s -> s.getStatus() == SubscriptionStatus.ACTIVE).orElse(false);
        return new SubscriptionStatusResponse(
                isPremium, latest.map(subscriptionMapper::toDto).orElse(null));
    }

    /** Consumido por {@code ProfileService.getMe} para poblar {@code UserProfileResponse.isPremium} (G5). */
    @Transactional(readOnly = true)
    public boolean isPremium(UUID userId) {
        return subscriptionRepository.existsByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE);
    }
}
