package co.edu.unbosque.bloomtrade.audit;

/**
 * Catálogo de tipos de evento auditables (ARCHITECTURE.md §12). Se extiende por feature; los de
 * HU-F01 son los de registro de inversionista.
 */
public enum AuditEventType {

    /** Registro de inversionista completado (post-commit) — spec HU-F01 §9.1. */
    USER_REGISTERED,

    /** Registro fallido por email duplicado o error técnico — spec HU-F01 §9.1. */
    USER_REGISTRATION_FAILED,

    /** Email de bienvenida no enviado; el registro sí persistió (warning) — spec HU-F01 §9.1. */
    WELCOME_EMAIL_FAILED,

    /** Email OTP de MFA no enviado; el login paso 1 sí generó sesión temporal. */
    OTP_EMAIL_FAILED,

    /** Email de bloqueo de cuenta no enviado; el bloqueo sí quedó aplicado en Redis. */
    ACCOUNT_LOCKED_EMAIL_FAILED,

    /**
     * Intento de inicio de sesión (paso 1). {@code result=ALLOWED} cuando las credenciales fueron
     * válidas y se emitió OTP; {@code DENIED} cuando falló (con {@code details.reason}).
     * Spec HU-F02 §9.1.
     */
    LOGIN_ATTEMPT,

    /**
     * Cuenta bloqueada automáticamente tras 3 intentos fallidos consecutivos (TAC-S3). Spec
     * HU-F02 §9.1; detail incluye {@code reason="MAX_LOGIN_ATTEMPTS"} y
     * {@code lockDurationSeconds=900}.
     */
    ACCOUNT_LOCKED,

    /**
     * OTP validado correctamente; el sistema emitió el access token (spec HU-F02 §9.1). detail
     * lleva {@code tempSessionDurationMs} para medir el tiempo entre login y verify.
     */
    MFA_VERIFIED,

    /**
     * OTP incorrecto, expirado, o sesión temporal inexistente (spec HU-F02 §9.1). detail lleva
     * {@code reason} en {@code INVALID_CODE | CODE_EXPIRED | SESSION_EXPIRED} y
     * {@code attemptNumber} cuando aplica.
     */
    MFA_FAILED,

    /** Nuevo OTP solicitado vía {@code /mfa/resend} (spec HU-F02 §9.1). */
    MFA_RESEND_REQUESTED,

    /**
     * Sesión temporal invalidada por 3 fallos de OTP o 3 reenvíos agotados (spec HU-F02 §9.1).
     * detail lleva {@code reason} en {@code MAX_ATTEMPTS | MAX_RESENDS}.
     */
    MFA_SESSION_INVALIDATED,

    /**
     * Perfil del usuario actualizado vía PATCH /api/v1/me (spec HU-F04+F20 §9.1). detail lleva
     * {@code changedFields: List<String>} — SOLO nombres de campo, NO valores (anti-PII).
     */
    PROFILE_UPDATED,

    /**
     * Cambio específico del canal de notificación (spec HU-F04+F20 §9.1). Se emite ADEMÁS de
     * {@code PROFILE_UPDATED} cuando {@code notificationChannel} es uno de los campos cambiados.
     * detail lleva {@code from, to} (enums, no PII).
     */
    NOTIFICATION_CHANNEL_CHANGED,

    /**
     * Error técnico durante la actualización del perfil (spec HU-F04+F20 §9.1). detail lleva
     * {@code reason="TECHNICAL_ERROR"} y {@code errorClass}.
     */
    PROFILE_UPDATE_FAILED,

    // ─── HU-F06 — Suscripción premium con Stripe ─────────────────────────────

    /** Checkout Session creada exitosamente (spec §9.1). detail: {@code plan, stripeSessionId, stripeCustomerId}. */
    CHECKOUT_SESSION_CREATED,

    /** Error al crear Checkout Session (spec §5.3.3). detail: {@code reason, stripeErrorCode}. */
    CHECKOUT_SESSION_FAILED,

    /** Customer Portal Session creada (spec v1.2 §9.1). detail: {@code stripeCustomerId}. */
    BILLING_PORTAL_SESSION_CREATED,

    /** Webhook checkout.session.completed procesado — alta de suscripción (spec §9.1). */
    SUBSCRIPTION_ACTIVATED,

    /**
     * Webhook customer.subscription.updated detectó {@code cancel_at_period_end} false→true
     * (cancelación desde el Customer Portal, v1.2 §5.2.2). detail: {@code subscriptionId, periodEnd}.
     */
    SUBSCRIPTION_CANCELLED_SCHEDULED,

    /**
     * Webhook customer.subscription.updated detectó {@code cancel_at_period_end} true→false
     * (reactivación desde el Customer Portal, v1.2). detail: {@code subscriptionId, periodEnd}.
     */
    SUBSCRIPTION_REACTIVATED,

    /** Webhook customer.subscription.deleted — la suscripción terminó (spec §9.1). */
    SUBSCRIPTION_TERMINATED,

    /**
     * Webhook invoice.payment_failed procesado — downgrade inmediato a PAST_DUE (D10 — sin
     * grace period en MVP).
     */
    SUBSCRIPTION_PAYMENT_FAILED,

    /** Webhook entró al endpoint, firma validada, idempotencia ok — listo para procesar. */
    STRIPE_WEBHOOK_RECEIVED,

    /** Webhook ya procesado antes (mismo {@code stripe_event_id}) — Stripe reintentó. */
    STRIPE_WEBHOOK_DUPLICATE,

    /** Webhook con firma HMAC inválida — posible spoofing (severidad WARNING). */
    STRIPE_WEBHOOK_SIGNATURE_FAILED,

    /** Webhook refiere subscription_id desconocido en BD — caso defensivo (severidad WARNING). */
    STRIPE_WEBHOOK_ORPHAN,

    /** Excepción durante el procesamiento del webhook (severidad ERROR — Stripe reintentará). */
    STRIPE_WEBHOOK_PROCESSING_FAILED,

    /** Email "bienvenido a premium" no enviado (paralelo a otros *_EMAIL_FAILED). */
    WELCOME_PREMIUM_EMAIL_FAILED,

    /** Email "tu suscripción se cancelará el X" no enviado. */
    CANCELLATION_SCHEDULED_EMAIL_FAILED,

    /** Email "tu suscripción premium ha terminado" no enviado. */
    SUBSCRIPTION_EXPIRED_EMAIL_FAILED,

    /** Email "tu pago falló" no enviado. */
    SUBSCRIPTION_PAYMENT_FAILED_EMAIL_FAILED
}
