package co.edu.unbosque.bloomtrade.auth.subscription.dto;

/**
 * Respuesta de {@code GET /api/v1/subscriptions/me} (spec HU-F06 §6.1.2).
 *
 * <p>{@code subscription} puede ser {@code null} si el usuario nunca ha tenido suscripción.
 * Cuando existe (incluyendo en estados terminales CANCELLED/PAST_DUE), se devuelve la más
 * reciente para que la UI muestre el histórico mientras ofrece re-suscribirse.
 */
public record SubscriptionStatusResponse(boolean isPremium, SubscriptionDto subscription) {}
