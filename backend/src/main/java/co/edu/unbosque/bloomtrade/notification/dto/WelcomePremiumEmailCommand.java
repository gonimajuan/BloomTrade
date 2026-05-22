package co.edu.unbosque.bloomtrade.notification.dto;

import co.edu.unbosque.bloomtrade.auth.subscription.domain.BillingPlan;
import java.time.Instant;

/** Command para email de bienvenida a Premium (HU-F06). */
public record WelcomePremiumEmailCommand(
        String userId,
        String toEmail,
        String nombreCompleto,
        BillingPlan plan,
        Instant currentPeriodEnd) {}
