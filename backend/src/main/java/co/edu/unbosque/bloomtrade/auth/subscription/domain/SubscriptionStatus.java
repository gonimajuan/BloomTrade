package co.edu.unbosque.bloomtrade.auth.subscription.domain;

/**
 * Estado del ciclo de vida de una suscripción (spec HU-F06 §7, CHECK
 * {@code chk_subscription_status}).
 *
 * <ul>
 *   <li>{@link #ACTIVE} — el usuario tiene acceso premium ahora mismo. Una sola por usuario
 *       (enforced en BD por {@code uq_one_active_subscription_per_user}).
 *   <li>{@link #CANCELLED} — terminada (ya pasó {@code current_period_end} de una cancelación
 *       programada, o se canceló inmediatamente). Estado terminal.
 *   <li>{@link #PAST_DUE} — pago de renovación falló (downgrade inmediato, MVP D10). Estado
 *       terminal — el usuario debe re-suscribirse para volver a premium.
 * </ul>
 */
public enum SubscriptionStatus {
    ACTIVE,
    CANCELLED,
    PAST_DUE
}
