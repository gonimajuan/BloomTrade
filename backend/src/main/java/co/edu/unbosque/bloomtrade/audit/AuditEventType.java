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
    PROFILE_UPDATE_FAILED
}
