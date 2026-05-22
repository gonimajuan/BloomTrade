package co.edu.unbosque.bloomtrade.auth.subscription.domain;

/**
 * Plan de cobro premium (spec HU-F06 §1, CHECK {@code chk_subscription_plan}).
 *
 * <p>Las dos Prices asociadas viven en Stripe Dashboard (Test Mode) y se referencian por env:
 * {@code STRIPE_PRICE_MONTHLY} (USD $12) y {@code STRIPE_PRICE_YEARLY} (USD $120).
 */
public enum BillingPlan {
    MONTHLY,
    YEARLY
}
