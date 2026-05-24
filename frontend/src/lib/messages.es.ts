// Mensajes humanos (ES) por código de error en SCREAMING_SNAKE — mirror del backend
// (validation-messages.properties, decisión D10 del plan). Punto único de localización del UI.

const MESSAGES: Record<string, string> = {
  // ── Genéricos ─────────────────────────────────────────────────────────────
  VALIDATION_REQUIRED: 'Este campo es obligatorio',
  VALIDATION_FAILED: 'Uno o más campos no superaron la validación',
  VALIDATION_INVALID_EMAIL: 'Formato de email inválido',
  INTERNAL_ERROR:
    'Error temporal del servidor. Por favor intenta de nuevo en unos momentos.',
  NETWORK_ERROR: 'No se pudo contactar al servidor. Revisa tu conexión.',
  UNKNOWN_ERROR: 'Ocurrió un error inesperado.',

  // ── HU-F01 (registro) ────────────────────────────────────────────────────
  WEAK_PASSWORD:
    'Password debe tener al menos 10 caracteres, una mayúscula, una minúscula y un número',
  VALIDATION_INVALID_NAME: 'Nombre inválido: 3 a 100 caracteres, solo letras, espacios y tildes',
  VALIDATION_INVALID_DOCUMENT_TYPE: 'Tipo de documento inválido (CC, CE o PASAPORTE)',
  VALIDATION_INVALID_DOCUMENT_NUMBER:
    'Número de documento inválido para el tipo seleccionado',
  VALIDATION_INVALID_PHONE:
    'Teléfono inválido: formato E.164, por ejemplo +573001234567',
  TERMS_NOT_ACCEPTED: 'Debe aceptar los términos y condiciones',
  EMAIL_ALREADY_REGISTERED: 'Este correo ya está registrado. ¿Iniciar sesión?',

  // ── HU-F02 + HU-F03 (login + MFA) ────────────────────────────────────────
  INVALID_CREDENTIALS: 'Credenciales inválidas.',
  ACCOUNT_NOT_ACTIVE: 'Tu cuenta no está activa. Contacta al administrador.',
  ACCOUNT_LOCKED:
    'Cuenta bloqueada temporalmente por demasiados intentos fallidos.',
  VALIDATION_INVALID_OTP: 'Código OTP inválido (debe ser 6 dígitos numéricos).',
  MFA_INVALID_CODE: 'Código incorrecto.',
  MFA_CODE_EXPIRED: 'El código ha expirado. Por favor solicita uno nuevo.',
  MFA_SESSION_INVALIDATED:
    'Demasiados intentos. Por favor inicia sesión de nuevo.',
  TEMP_SESSION_INVALID:
    'Tu sesión ha expirado. Por favor inicia sesión de nuevo.',
  RESEND_COOLDOWN_ACTIVE: 'Espera unos segundos antes de solicitar otro código.',
  MAX_RESENDS_EXCEEDED:
    'Has alcanzado el máximo de reenvíos. Por favor inicia sesión de nuevo.',
  TOKEN_EXPIRED: 'Tu sesión expiró. Inicia sesión de nuevo.',
  TOKEN_INVALID: 'Tu sesión no es válida. Inicia sesión de nuevo.',

  // ── HU-F04 + HU-F20 (perfil + canal de notificación) ────────────────────
  READ_ONLY_FIELD_MODIFIED: 'Ese campo no puede ser modificado desde el perfil.',
  INVALID_TICKER: 'El ticker seleccionado no está en el catálogo permitido.',
  TOO_MANY_TICKERS: 'No puedes seleccionar más de 25 tickers.',
  DUPLICATE_TICKERS: 'La lista de tickers no puede contener duplicados.',
  VALIDATION_INVALID_CHANNEL:
    'Canal de notificación inválido (debe ser EMAIL, SMS o WHATSAPP).',

  // ── HU-F06 (suscripción premium con Stripe) ──────────────────────────────
  SUBSCRIPTION_ALREADY_ACTIVE: 'Ya tienes una suscripción activa.',
  NO_STRIPE_CUSTOMER: 'No tienes una suscripción que gestionar todavía.',
  STRIPE_API_ERROR:
    'El servicio de pagos no responde. Intenta de nuevo en unos minutos.',
  VALIDATION_INVALID_PLAN: 'Plan inválido (debe ser MONTHLY o YEARLY).',

  // ── HU-F09 (orden de compra Market con Alpaca paper trading) ─────────────
  // Nota: INVALID_TICKER y ACCOUNT_NOT_ACTIVE ya existen arriba (HU-F04/F02) y se reusan.
  INVALID_QUANTITY: 'La cantidad debe ser un entero positivo entre 1 y 10000.',
  INVALID_SIDE: 'Operación inválida.',
  SIDE_NOT_YET_IMPLEMENTED: 'La venta estará disponible próximamente.',
  INVALID_CLIENT_ORDER_ID:
    'Error técnico al generar la orden. Recarga la página.',
  INSUFFICIENT_FUNDS:
    'Saldo insuficiente para esta orden. Pide otro quote con menos cantidad.',
  ALPACA_API_ERROR:
    'El mercado no respondió. Tu saldo está intacto. Intenta de nuevo.',
  ALPACA_ORDER_REJECTED: 'El mercado rechazó tu orden. Intenta con otro ticker.',
  MARKET_DATA_UNAVAILABLE:
    'No se pudo obtener el precio actual. Intenta de nuevo.',
  MARKET_CLOSED:
    'El mercado está cerrado en este momento. Vuelve a intentarlo en horario de mercado.',
  ORDER_DUPLICATE_NOT_AN_ERROR: 'Tu orden ya estaba registrada.',

  // ── HU-F10 (orden de venta Market) ───────────────────────────────────────
  SHORT_SELLING_NOT_ALLOWED:
    'No tienes posición en este ticker. BloomTrade no permite ventas en corto.',
  INSUFFICIENT_SHARES:
    'No tienes suficientes acciones para vender la cantidad solicitada.',
};

export function humanFor(code: string): string {
  return MESSAGES[code] ?? code;
}
