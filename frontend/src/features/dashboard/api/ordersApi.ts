import { apiClient } from '@/lib/apiClient';
import type { OrderHistoryResponse, OrderSide } from '@/types/api';

export interface FetchOrdersParams {
  page?: number;
  size?: number;
  ticker?: string;
  side?: OrderSide;
}

/**
 * GET /api/v1/orders (HU-F17). Filtros opcionales `ticker` y `side`, paginación standard
 * Spring Data (`page` 0-indexed, `size` cap 100). Sort fijo backend: `submittedAt DESC`.
 */
export async function getOrders(
  params: FetchOrdersParams = {},
): Promise<OrderHistoryResponse> {
  const { data } = await apiClient.get<OrderHistoryResponse>('/orders', { params });
  return data;
}
