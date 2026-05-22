package co.edu.unbosque.bloomtrade.notification.dto;

import co.edu.unbosque.bloomtrade.auth.subscription.domain.BillingPlan;

/** Command para email "tu suscripción premium ha terminado" (HU-F06 §5.2.3). */
public record SubscriptionExpiredEmailCommand(
        String userId, String toEmail, String nombreCompleto, BillingPlan plan) {}
