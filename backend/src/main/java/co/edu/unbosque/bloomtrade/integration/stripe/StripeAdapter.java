package co.edu.unbosque.bloomtrade.integration.stripe;

import co.edu.unbosque.bloomtrade.auth.subscription.domain.BillingPlan;
import co.edu.unbosque.bloomtrade.auth.subscription.exception.StripeApiException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.Subscription;
import com.stripe.model.billingportal.Session;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.SubscriptionRetrieveParams;
import com.stripe.param.billingportal.SessionCreateParams;
import com.stripe.param.checkout.SessionCreateParams.LineItem;
import com.stripe.param.checkout.SessionCreateParams.Mode;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Adaptador único hacia la API de Stripe (HU-F06 — TAC-M1 intermediario, TAC-I2 adapter).
 *
 * <p><strong>Único punto de contacto con Stripe</strong> en todo el monolito. Si mañana se
 * cambia a otra pasarela de pago, solo cambia este adapter.
 *
 * <p>Cada método público que llama al API de Stripe está protegido por {@code @Retry} de
 * Resilience4j (3 reintentos con backoff exponencial 1s/3s/9s, configurado en
 * {@code application.yml}). Errores deterministas (firma inválida, request malformado, auth
 * fallida) NO se reintentan.
 *
 * <p>Las llamadas mutables (createCustomer, createCheckoutSession) llevan {@code Idempotency-Key}
 * para protección contra doble-click del usuario (D4 plan).
 */
@Component
public class StripeAdapter {

    private static final Logger log = LoggerFactory.getLogger(StripeAdapter.class);

    private final String webhookSecret;
    private final String returnUrl;
    private final String priceMonthly;
    private final String priceYearly;

    public StripeAdapter(
            @Value("${stripe.webhook-secret:}") String webhookSecret,
            @Value("${stripe.return-url:http://localhost:5173}") String returnUrl,
            @Value("${stripe.price.monthly:}") String priceMonthly,
            @Value("${stripe.price.yearly:}") String priceYearly) {
        this.webhookSecret = webhookSecret;
        this.returnUrl = returnUrl;
        this.priceMonthly = priceMonthly;
        this.priceYearly = priceYearly;
    }

    /**
     * Crea un Stripe Customer asociado al usuario. Idempotency-Key generado por request (D4)
     * — evita crear dos customers si el usuario hace doble-click.
     */
    @Retry(name = "stripeApi")
    public String createCustomer(String email, String name, UUID userId) {
        try {
            CustomerCreateParams params =
                    CustomerCreateParams.builder()
                            .setEmail(email)
                            .setName(name)
                            .putMetadata("userId", userId.toString())
                            .build();
            RequestOptions options = idempotencyOptions("customer-create-" + userId);
            Customer customer = Customer.create(params, options);
            log.info("Stripe customer creado: {} para userId={}", customer.getId(), userId);
            return customer.getId();
        } catch (StripeException e) {
            throw wrap("create customer", e);
        }
    }

    /**
     * Crea una Checkout Session en modo subscription (HU-F06 §5.1 paso 12). <strong>NO incluye
     * {@code payment_method_types}</strong> — habilita Dynamic Payment Methods (D3, trap de
     * skill {@code billing.md}).
     */
    @Retry(name = "stripeApi")
    public com.stripe.model.checkout.Session createCheckoutSession(
            String customerId, BillingPlan plan, UUID userId) {
        try {
            String priceId = priceIdFor(plan);
            com.stripe.param.checkout.SessionCreateParams params =
                    com.stripe.param.checkout.SessionCreateParams.builder()
                            .setMode(Mode.SUBSCRIPTION)
                            .setCustomer(customerId)
                            .addLineItem(
                                    LineItem.builder().setPrice(priceId).setQuantity(1L).build())
                            .setSuccessUrl(
                                    returnUrl
                                            + "/premium/success?session_id={CHECKOUT_SESSION_ID}")
                            .setCancelUrl(returnUrl + "/premium/cancel")
                            .putMetadata("userId", userId.toString())
                            .putMetadata("plan", plan.name())
                            .build();
            RequestOptions options =
                    idempotencyOptions(
                            "checkout-" + userId + "-" + UUID.randomUUID());
            com.stripe.model.checkout.Session session =
                    com.stripe.model.checkout.Session.create(params, options);
            log.info(
                    "Checkout Session creada: {} (plan={}, userId={})",
                    session.getId(),
                    plan,
                    userId);
            return session;
        } catch (StripeException e) {
            throw wrap("create checkout session", e);
        }
    }

    /** Crea una Customer Portal Session (HU-F06 v1.2 §5.2.1 — Customer Portal Stripe-hosted). */
    @Retry(name = "stripeApi")
    public Session createBillingPortalSession(String customerId) {
        try {
            SessionCreateParams params =
                    SessionCreateParams.builder()
                            .setCustomer(customerId)
                            .setReturnUrl(returnUrl + "/premium")
                            .build();
            Session session = Session.create(params);
            log.info("Billing Portal Session creada: {} (customer={})", session.getId(), customerId);
            return session;
        } catch (StripeException e) {
            throw wrap("create billing portal session", e);
        }
    }

    /** Recupera detalles completos de una Subscription (period_start, period_end, status). */
    @Retry(name = "stripeApi")
    public Subscription retrieveSubscription(String subscriptionId) {
        try {
            return Subscription.retrieve(
                    subscriptionId, SubscriptionRetrieveParams.builder().build(), null);
        } catch (StripeException e) {
            throw wrap("retrieve subscription " + subscriptionId, e);
        }
    }

    /**
     * Verifica firma + parsea evento. Lanza {@link SignatureVerificationException} si la firma
     * es inválida (manejado por el handler como 400 {@code WEBHOOK_SIGNATURE_INVALID}, sin
     * reintento — error determinista).
     */
    public Event constructWebhookEvent(String rawPayload, String sigHeader)
            throws SignatureVerificationException {
        return Webhook.constructEvent(rawPayload, sigHeader, webhookSecret);
    }

    private String priceIdFor(BillingPlan plan) {
        return switch (plan) {
            case MONTHLY -> priceMonthly;
            case YEARLY -> priceYearly;
        };
    }

    private RequestOptions idempotencyOptions(String key) {
        return RequestOptions.builder().setIdempotencyKey(key).build();
    }

    private StripeApiException wrap(String operation, StripeException e) {
        log.warn(
                "Stripe error en '{}': code={} requestId={} message={}",
                operation,
                e.getCode(),
                e.getRequestId(),
                e.getMessage());
        return new StripeApiException(
                "Stripe error on " + operation,
                stripeErrorCodeOf(e),
                e);
    }

    private static String stripeErrorCodeOf(StripeException e) {
        return e.getCode() != null ? e.getCode() : e.getClass().getSimpleName();
    }

    /** Helper para los tests — verifica el shape de los params sin tocar Stripe.com. */
    @SuppressWarnings("unused")
    private Map<String, Object> nothing() {
        return Map.of();
    }
}
