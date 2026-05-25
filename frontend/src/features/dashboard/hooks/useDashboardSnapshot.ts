import { useQuery } from '@tanstack/react-query';
import { getDashboardSnapshot } from '@/features/dashboard/api/dashboardApi';
import { parseError, type ParsedError } from '@/lib/errorParser';
import type { DashboardSnapshotResponse } from '@/types/api';

/**
 * GET /dashboard/snapshot (HU-F18 plan C5):
 *  - Polling intervalado cada 30s (`refetchInterval`).
 *  - `refetchIntervalInBackground: true` — sigue refrescando con la pestaña oculta.
 *  - `staleTime` 25s para evitar dobles fetches cerca del boundary del intervalo.
 *  - Cache hits del backend (Redis TTL 30s) amortiguan el costo Alpaca.
 */
export function useDashboardSnapshot() {
  return useQuery<DashboardSnapshotResponse, ParsedError>({
    queryKey: ['dashboard', 'snapshot'],
    queryFn: async () => {
      try {
        return await getDashboardSnapshot();
      } catch (err) {
        throw parseError(err);
      }
    },
    staleTime: 25_000,
    refetchInterval: 30_000,
    refetchIntervalInBackground: true,
  });
}
