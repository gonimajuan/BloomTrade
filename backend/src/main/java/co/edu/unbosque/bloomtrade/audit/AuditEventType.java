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
    ACCOUNT_LOCKED
}
