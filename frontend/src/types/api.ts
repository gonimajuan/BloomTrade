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
  isPremium: boolean; // HU-F06 G5 — agregado por la integración de subscription
  createdAt: string;
  updatedAt: string;
}

// ─── HU-F06 (suscripción premium con Stripe) ────────────────────────────────

export type BillingPlan = 'MONTHLY' | 'YEARLY';
export type SubscriptionStatus = 'ACTIVE' | 'CANCELLED' | 'PAST_DUE';

export interface CheckoutSessionRequest {
  plan: BillingPlan;
}

export interface CheckoutSessionResponse {
  checkoutUrl: string;
  sessionId: string;
}

export interface PortalSessionResponse {
  portalUrl: string;
}

export interface SubscriptionDto {
  id: string;
  plan: BillingPlan;
  status: SubscriptionStatus;
  currentPeriodStart: string;
  currentPeriodEnd: string;
  cancelAtPeriodEnd: boolean;
  createdAt: string;
}

export interface SubscriptionStatusResponse {
  isPremium: boolean;
  subscription: SubscriptionDto | null;
}

/** Todos los campos son opcionales: `null`/ausente = no enviar; lista vacía = limpiar. */
export interface UpdateProfileRequest {
  nombreCompleto?: string;
  telefono?: string;
  notificationChannel?: NotificationChannel;
  tickersOfInterest?: string[];
}

// ─── HU-F09 (orden de compra Market con Alpaca paper trading) ──────────────

export type OrderSide = 'BUY' | 'SELL';
export type OrderType = 'MARKET';
export type OrderStatus = 'PENDING' | 'EXECUTED' | 'REJECTED' | 'FAILED';

export interface QuoteRequest {
  ticker: string;
  side: OrderSide;
  quantity: number;
}

export interface QuoteResponse {
  ticker: string;
  side: OrderSide;
  quantity: number;
  estimatedUnitPrice: string;
  estimatedSubtotal: string;
  commission: string;
  estimatedTotal: string;
  currency: string;
  userBalance: string;
  sufficientFunds: boolean;
  marketOpen: boolean;
  quotedAt: string;
}

export interface PlaceOrderRequest {
  clientOrderId: string;
  ticker: string;
  side: OrderSide;
  type: OrderType;
  quantity: number;
}

export interface OrderResponse {
  id: string;
  clientOrderId: string;
  ticker: string;
  side: OrderSide;
  type: OrderType;
  quantity: number;
  quotedUnitPrice: string;
  executionUnitPrice: string | null;
  commission: string;
  quotedTotal: string | null;
  executionTotal: string | null;
  status: OrderStatus;
  alpacaOrderId: string | null;
  errorCode: string | null;
  errorMessage: string | null;
  submittedAt: string;
  executedAt: string | null;
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
