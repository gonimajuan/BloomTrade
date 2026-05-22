package co.edu.unbosque.bloomtrade.auth.subscription.dto;

import co.edu.unbosque.bloomtrade.auth.subscription.domain.BillingPlan;
import co.edu.unbosque.bloomtrade.auth.subscription.domain.SubscriptionStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Vista pública de una {@link co.edu.unbosque.bloomtrade.auth.subscription.domain.Subscription}.
 *
 * <p><strong>NO contiene {@code stripeCustomerId} ni {@code stripeSubscriptionId}</strong>
 * (HU-F06 §10.2 constraint NO-NEGOCIABLE — D21 plan). El {@code SubscriptionMapper} respeta
 * esta exclusión y el test {@code SubscriptionMapperTest} verifica el JSON serializado.
 */
public record SubscriptionDto(
        UUID id,
        BillingPlan plan,
        SubscriptionStatus status,
        Instant currentPeriodStart,
        Instant currentPeriodEnd,
        boolean cancelAtPeriodEnd,
        Instant createdAt) {}
