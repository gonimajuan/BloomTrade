package co.edu.unbosque.bloomtrade.notification.dto;

import co.edu.unbosque.bloomtrade.auth.subscription.domain.BillingPlan;

/** Command para email "tu pago falló — actualiza tu método" (HU-F06 §5.2.4). */
public record SubscriptionPaymentFailedEmailCommand(
        String userId, String toEmail, String nombreCompleto, BillingPlan plan) {}
