import { apiClient } from '@/lib/apiClient';
import type {
  OrderResponse,
  PlaceOrderRequest,
  QuoteRequest,
  QuoteResponse,
} from '@/types/api';

/** POST /api/v1/orders/quote — quote informativo (no persiste, no descuenta). */
export async function requestQuote(
  payload: QuoteRequest,
): Promise<QuoteResponse> {
  const { data } = await apiClient.post<QuoteResponse>(
    '/orders/quote',
    payload,
  );
  return data;
}

/**
 * Resultado del submit con flag de idempotencia.
 * El backend devuelve 201 cuando crea la orden y 200 cuando devuelve una orden
 * preexistente con el mismo `clientOrderId` (SPEC §6.1.2).
 */
export interface PlaceOrderResult {
  data: OrderResponse;
  isIdempotent: boolean;
}

/** POST /api/v1/orders — crea y ejecuta la orden Market (idempotente por clientOrderId). */
export async function placeOrder(
  payload: PlaceOrderRequest,
): Promise<PlaceOrderResult> {
  const response = await apiClient.post<OrderResponse>('/orders', payload);
  return {
    data: response.data,
    isIdempotent: response.status === 200,
  };
}
