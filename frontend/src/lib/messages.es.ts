// Mensajes humanos (ES) por código de error en SCREAMING_SNAKE — mirror del backend
// (validation-messages.properties, decisión D10 del plan). Punto único de localización del UI.

const MESSAGES: Record<string, string> = {
  VALIDATION_REQUIRED: 'Este campo es obligatorio',
  VALIDATION_FAILED: 'Uno o más campos no superaron la validación',
  VALIDATION_INVALID_EMAIL: 'Formato de email inválido',
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
  INTERNAL_ERROR:
    'Error temporal del servidor. Por favor intenta de nuevo en unos momentos.',
  NETWORK_ERROR: 'No se pudo contactar al servidor. Revisa tu conexión.',
  UNKNOWN_ERROR: 'Ocurrió un error inesperado.',
};

export function humanFor(code: string): string {
  return MESSAGES[code] ?? code;
}
