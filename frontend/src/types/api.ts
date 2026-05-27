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
export type OrderStatus =
  | 'PENDING'
  | 'EXECUTED'
  | 'REJECTED'
  | 'FAILED'
  | 'CANCELED' // HU-F15
  | 'EXPIRED'; // HU-F15

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
  /**
   * Side-aware (HU-F10):
   * - BUY: subtotal + commission (lo que se DESCONTARÁ del saldo).
   * - SELL: subtotal − commission (lo que se ACREDITARÁ al saldo, producto neto).
   */
  estimatedTotal: string;
  currency: string;
  userBalance: string;
  sufficientFunds: boolean;
  /**
   * HU-F10. Solo significativo para SELL: true si el usuario tiene posición ≥ quantity.
   * Para BUY siempre true (no aplica).
   */
  sufficientShares: boolean;
  /**
   * HU-F10. Cantidad actual del usuario en el ticker.
   * - BUY: 0 si no tiene posición (informativo).
   * - SELL: cantidad disponible para validar contra quantity solicitada.
   */
  userShares: number;
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
  // HU-F15 (nullable; @JsonInclude(NON_NULL) los omite cuando no aplican)
  canceledAt?: string | null;
  cancelRequestedAt?: string | null;
  expiredAt?: string | null;
  /** Solo BUY canceladas/expiradas: monto refundido al balance (string para precisión BigDecimal). */
  refundedAmount?: string | null;
  /** Solo SELL canceladas/expiradas: cantidad restaurada a la posición. */
  restoredQty?: number | null;
}

// ─── HU-F16 + HU-F21 (portafolio y saldo) ──────────────────────────────────

export interface BalanceResponse {
  balance: string;
  currency: string;
  lastUpdatedAt: string;
}

export interface PositionDto {
  ticker: string;
  quantity: number;
  avgCost: string;
  costBasis: string;
  currency: string;
  /** null si Alpaca data API falló o timeout para este ticker. */
  currentPrice: string | null;
  marketValue: string | null;
  /** Con signo. */
  unrealizedPnL: string | null;
  unrealizedPnLPct: string | null;
}

export interface PendingOrderDto {
  orderId: string;
  clientOrderId: string;
  ticker: string;
  side: OrderSide;
  quantity: number;
  submittedAt: string;
  quotedTotal: string | null;
  /** HU-F15: si seteado, el cancel está en polling-timeout esperando que reconcile lazy v2 lo materialice. */
  cancelRequestedAt?: string | null;
}

/** Estado del mark-to-market. Mapea el {@code marketDataAvailable} top-level del response. */
export type MarketDataAvailability = 'true' | 'partial' | 'false';

export interface PortfolioPositionsResponse {
  positions: PositionDto[];
  pendingOrders: PendingOrderDto[];
  marketDataAvailable: MarketDataAvailability;
  fetchedAt: string;
}

// ─── HU-F18 (dashboard de acciones) ────────────────────────────────────────

export type Market = 'NYSE' | 'NASDAQ' | 'LSE' | 'TSE' | 'ASX';

export interface TickerDashboardDto {
  ticker: string;
  /** Mid-price actual. null si Alpaca falló para este ticker. */
  currentPrice: string | null;
  /** Close de la primera barra intradía hoy. */
  openPrice: string | null;
  /** (current − open) / open × 100, scale=2. null si current u open están en null o open=0. */
  dayChangePct: string | null;
  /** Serie cronológica de closes intradía (15Min) como strings scale=2. Vacía si bars no disponibles. */
  sparkline: string[];
}

export interface MarketGroupDto {
  market: Market;
  items: TickerDashboardDto[];
}

export interface AccountEquityDto {
  /** Saldo USD disponible (siempre presente). */
  balance: string;
  /** Σ(qty × currentPrice). null si Alpaca falló para todas las posiciones del usuario. */
  positionsMarketValue: string | null;
  /** balance + positionsMarketValue. null si positionsMarketValue es null. */
  equity: string | null;
  /** Σ(qty × avgBuyPrice). null si no hay posiciones (sin posiciones → "0.00"). */
  costBasisTotal: string | null;
  /** Con signo. null si market value es null. */
  unrealizedPnL: string | null;
  /** (pnl / costBasis) × 100. null si costBasis=0 o pnl null. */
  unrealizedPnLPct: string | null;
  currency: 'USD';
}

export interface DashboardSnapshotResponse {
  tickers: MarketGroupDto[];
  equity: AccountEquityDto;
  marketDataAvailable: MarketDataAvailability;
  fetchedAt: string;
}

// ─── HU-F17 (historial de órdenes) ─────────────────────────────────────────

export interface OrderHistoryDto {
  orderId: string;
  clientOrderId: string;
  ticker: string;
  side: OrderSide;
  quantity: number;
  status: OrderStatus;
  submittedAt: string;
  executedAt: string | null;
  executionTotal: string | null;
  averageFillPrice: string | null;
  commission: string | null;
  alpacaOrderId: string | null;
  failureReason: string | null;
}

export interface PaginationDto {
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface OrderHistoryResponse {
  content: OrderHistoryDto[];
  pagination: PaginationDto;
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
