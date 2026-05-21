// Tipos del contrato HTTP — mirror manual del backend. Cuando el backend está arriba:
// `npm run gen:api` puede generar tipos desde /v3/api-docs (STACK.md §3.4); por ahora se
// mantienen aquí para no acoplar el dev del frontend a tener el backend corriendo.

export type DocumentType = 'CC' | 'CE' | 'PASAPORTE';
export type UserRole = 'INVESTOR' | 'BROKER' | 'ADMIN' | 'LEGAL' | 'BOARD';
export type UserStatus = 'ACTIVE' | 'BLOCKED' | 'SUSPENDED';

// ─── HU-F01 (registro) ──────────────────────────────────────────────────────

export interface RegisterRequest {
  email: string;
  password: string;
  nombreCompleto: string;
  tipoDocumento: DocumentType;
  numeroDocumento: string;
  telefono: string;
  aceptaTerminos: boolean;
}

export interface RegisterResponse {
  id: string;
  email: string;
  nombreCompleto: string;
  rol: UserRole;
  estado: UserStatus;
  createdAt: string;
}

// ─── HU-F02 + HU-F03 (login + MFA) ──────────────────────────────────────────

export interface LoginRequest {
  email: string;
  password: string;
}

export interface LoginResponse {
  tempSessionId: string;
  expiresInSeconds: number;
}

export interface MfaVerifyRequest {
  tempSessionId: string;
  code: string;
}

/** Datos del usuario autenticado reutilizables en endpoints posteriores. */
export interface UserSummary {
  id: string;
  email: string;
  nombreCompleto: string;
  rol: UserRole;
}

export interface MfaVerifyResponse {
  accessToken: string;
  expiresIn: number;
  user: UserSummary;
}

export interface MfaResendRequest {
  tempSessionId: string;
}

export interface MfaResendResponse {
  expiresInSeconds: number;
  resendsRemaining: number;
}

// ─── HU-F04 + HU-F20 (perfil + canal de notificación) ──────────────────────

export type NotificationChannel = 'EMAIL' | 'SMS' | 'WHATSAPP';

export interface UserProfileResponse {
  id: string;
  email: string;
  nombreCompleto: string;
  tipoDocumento: DocumentType;
  numeroDocumento: string;
  telefono: string;
  rol: UserRole;
  estado: UserStatus;
  notificationChannel: NotificationChannel;
  tickersOfInterest: string[];
  createdAt: string;
  updatedAt: string;
}

/** Todos los campos son opcionales: `null`/ausente = no enviar; lista vacía = limpiar. */
export interface UpdateProfileRequest {
  nombreCompleto?: string;
  telefono?: string;
  notificationChannel?: NotificationChannel;
  tickersOfInterest?: string[];
}

// ─── Errores estándar ───────────────────────────────────────────────────────

export interface FieldErrorItem {
  field: string;
  code: string;
  message: string;
}

export interface ErrorResponse {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  path: string;
  traceId: string;
  fieldErrors?: FieldErrorItem[];
}
