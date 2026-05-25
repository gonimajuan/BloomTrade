import { useQuery } from '@tanstack/react-query';
import { getOrders } from '@/features/dashboard/api/ordersApi';
import { parseError, type ParsedError } from '@/lib/errorParser';
import type { OrderHistoryResponse } from '@/types/api';

/**
 * GET /orders?page=0&size=10 (HU-F17 widget embebido en /dashboard, plan C8).
 * Mismo refetchInterval que el snapshot para mantener coherencia visual cuando el usuario
 * opera en /trade y vuelve.
 */
export function useOrdersRecent() {
  return useQuery<OrderHistoryResponse, ParsedError>({
    queryKey: ['orders', 'recent'],
    queryFn: async () => {
      try {
        return await getOrders({ page: 0, size: 10 });
      } catch (err) {
        throw parseError(err);
      }
    },
    staleTime: 25_000,
    refetchInterval: 30_000,
    refetchIntervalInBackground: true,
  });
}
