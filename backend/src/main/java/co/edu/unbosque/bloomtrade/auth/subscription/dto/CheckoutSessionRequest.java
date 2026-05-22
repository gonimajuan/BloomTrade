package co.edu.unbosque.bloomtrade.auth.subscription.dto;

import co.edu.unbosque.bloomtrade.auth.subscription.domain.BillingPlan;
import jakarta.validation.constraints.NotNull;

/** Payload de {@code POST /api/v1/subscriptions/checkout-session} (spec HU-F06 §6.1.1). */
public record CheckoutSessionRequest(
        @NotNull(message = "VALIDATION_INVALID_PLAN") BillingPlan plan) {}
