import { apiClient } from '@/lib/apiClient';
import type {
  CheckoutSessionRequest,
  CheckoutSessionResponse,
  PortalSessionResponse,
  SubscriptionStatusResponse,
} from '@/types/api';

/** GET /api/v1/subscriptions/me — estado de suscripción del usuario autenticado. */
export async function getSubscriptionStatus(): Promise<SubscriptionStatusResponse> {
  const { data } = await apiClient.get<SubscriptionStatusResponse>(
    '/subscriptions/me',
  );
  return data;
}

/** POST /api/v1/subscriptions/checkout-session — inicia flujo de pago. */
export async function createCheckoutSession(
  payload: CheckoutSessionRequest,
): Promise<CheckoutSessionResponse> {
  const { data } = await apiClient.post<CheckoutSessionResponse>(
    '/subscriptions/checkout-session',
    payload,
  );
  return data;
}

/** POST /api/v1/subscriptions/portal-session — abre Customer Portal (v1.2). */
export async function openBillingPortal(): Promise<PortalSessionResponse> {
  const { data } = await apiClient.post<PortalSessionResponse>(
    '/subscriptions/portal-session',
  );
  return data;
}
