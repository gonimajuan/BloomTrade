package co.edu.unbosque.bloomtrade.auth.subscription.domain;

/**
 * Estado del procesamiento de un webhook de Stripe (CHECK {@code chk_webhook_status}).
 *
 * <ul>
 *   <li>{@link #RECEIVED} — el INSERT se hizo; falta procesar el negocio.
 *   <li>{@link #PROCESSED} — procesado exitosamente.
 *   <li>{@link #FAILED} — el procesamiento lanzó excepción y se hizo rollback. Stripe reintentará.
 *   <li>{@link #DUPLICATE} — el {@code stripe_event_id} ya existía (idempotencia).
 *   <li>{@link #ORPHAN} — el webhook referencia un {@code stripe_subscription_id} no presente en BD.
 * </ul>
 */
public enum WebhookEventStatus {
    RECEIVED,
    PROCESSED,
    FAILED,
    DUPLICATE,
    ORPHAN
}
