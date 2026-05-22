package co.edu.unbosque.bloomtrade.auth.subscription.dto;

/** Respuesta exitosa de {@code POST /subscriptions/checkout-session} (HU-F06 §6.1.1). */
public record CheckoutSessionResponse(String checkoutUrl, String sessionId) {}
