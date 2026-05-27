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
    SUBSCRIPTION_PAYMENT_FAILED_EMAIL_FAILED,

    // ─── HU-F09 — Orden de compra Market ─────────────────────────────────────

    /** Orden persistida en estado PENDING tras validaciones iniciales (SPEC §9.1). */
    ORDER_CREATED,

    /**
     * Orden ejecutada exitosamente — Alpaca confirmó {@code filled}, débito + upsertPosition
     * completados (SPEC §9.1). detail: {@code orderId, alpacaOrderId, executionUnitPrice,
     * executionTotal, commission}.
     */
    ORDER_EXECUTED,

    /**
     * Orden rechazada. Razones: {@code INSUFFICIENT_FUNDS} (race con débito), o
     * {@code ALPACA_ORDER_REJECTED} (símbolo no soportado, qty inválida del lado Alpaca).
     * detail: {@code orderId, reason, alpacaReason?}.
     */
    ORDER_REJECTED,

    /**
     * Orden falló por error técnico: Alpaca caída tras 3 retries, market data caído. Saldo NO
     * descontado. detail: {@code orderId, errorCode, errorMessage}.
     */
    ORDER_FAILED,

    /**
     * Orden encolada en Alpaca pero NO ejecutada dentro de la ventana de polling síncrono —
     * típicamente porque el mercado está cerrado (HU-F09 D29 emergente Lote H.5). Saldo SÍ
     * descontado (reserva). detail: {@code orderId, alpacaOrderId, quotedTotal, reason}.
     */
    ORDER_QUEUED,

    /**
     * Request idempotente: el {@code clientOrderId} ya existía en BD. NO se llamó a Alpaca, NO
     * se debitó. detail: {@code existingOrderId, clientOrderId}.
     */
    ORDER_DUPLICATE_REQUEST,

    /**
     * Usuario con {@code estado != ACTIVE} intentó operar (SPEC §5.3.10). detail:
     * {@code accountStatus}.
     */
    ORDER_BLOCKED_BY_ACCOUNT_STATUS,

    /**
     * Falló el quote por error en Alpaca Data API (caída post-retries o 429 sostenido).
     * detail: {@code ticker, reason}.
     */
    QUOTE_FAILED,

    /** Email "tu orden fue ejecutada" no enviado. */
    ORDER_EXECUTED_EMAIL_FAILED,

    /** Email "tu orden fue rechazada por el mercado" no enviado. */
    ORDER_REJECTED_EMAIL_FAILED,

    /** Email "tu orden no pudo procesarse" no enviado. */
    ORDER_FAILED_EMAIL_FAILED,

    /** Email "tu orden quedó en cola" no enviado. */
    ORDER_QUEUED_EMAIL_FAILED,

    // ─── HU-F15 — Cancelar orden Market ──────────────────────────────────────

    /**
     * Cancelación solicitada por el usuario. Se emite ANTES del DELETE a Alpaca, dentro de la
     * transacción del cancel. {@code details: { orderId, alpacaOrderId, side, ticker, quantity,
     * quotedTotal, outcome }} donde {@code outcome} ∈ {"CANCELED", "PENDING_CANCEL", "RACE_FILLED"}
     * dependiendo del resultado del polling (SPEC §5.1 paso 9 + §9.1).
     */
    ORDER_CANCEL_REQUESTED,

    /**
     * Transición {@code PENDING → CANCELED} aplicada. Emitido desde {@code TradingService}
     * (polling-OK) o desde {@code OrderReconciliationService} v2 (reconcile lazy materializa
     * un PENDING+cancelRequestedAt o un Alpaca canceled outbound). {@code details: { orderId,
     * alpacaOrderId, side, ticker, quantity, refundedAmount/restoredQty, canceledAt, source }}
     * donde {@code source} ∈ {"USER_REQUEST", "BROKER_CANCEL", "DRIFT_RECONCILE"}.
     */
    ORDER_CANCELED,

    /**
     * Transición {@code PENDING → EXPIRED} aplicada vía reconcile lazy v2 (Alpaca reportó
     * {@code expired} — TIF day terminó sin fill). El reverse se aplica igual que en CANCELED.
     * {@code details: { orderId, alpacaOrderId, side, ticker, quantity, refundedAmount/restoredQty,
     * expiredAt }}.
     */
    ORDER_EXPIRED,

    /**
     * Request idempotente: el usuario re-cliqueó "Cancelar" sobre orden ya CANCELED o con
     * {@code cancel_requested_at} ya seteado. NO se llamó a Alpaca, NO se ejecutó segundo
     * refund. {@code details: { orderId, existingStatus, alreadyCanceledAt o cancelRequestedAt }}.
     */
    ORDER_DUPLICATE_CANCEL_REQUEST,

    /**
     * Usuario intentó cancelar orden en estado terminal no cancelable ({@code EXECUTED},
     * {@code REJECTED}, {@code FAILED}, {@code EXPIRED}). Respuesta 409. {@code details:
     * { orderId, currentStatus, reason: "NOT_CANCELABLE" }}.
     */
    ORDER_CANCEL_REJECTED,

    /**
     * Cancel falló por error técnico: Alpaca caído tras 3 retries o respuesta inesperada.
     * Respuesta 502. Estado BD intacto (orden sigue PENDING, sin cancel_requested_at).
     * {@code details: { orderId, reason: "BROKER_UNAVAILABLE" o "UNEXPECTED_STATUS" }}.
     */
    ORDER_CANCEL_FAILED,

    /**
     * Email "Tu orden fue cancelada/expiró" no se pudo enviar (SMTP down, MailHog caído, etc.).
     * El estado de la orden y el reverse de balance/position SÍ se aplicaron — esto es solo el
     * efecto colateral de notificación. {@code details: { orderId, side, isExpired }}.
     */
    ORDER_CANCEL_EMAIL_FAILED
}
