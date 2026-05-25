import { useQuery } from '@tanstack/react-query';
import { getPositions } from '@/features/portfolio/api/portfolioApi';
import { parseError, type ParsedError } from '@/lib/errorParser';
import type { PortfolioPositionsResponse } from '@/types/api';

/**
 * GET /portfolio/positions (HU-F16). Mark-to-market degradable + sección pending orders.
 * Plan D8: stale 30s + refetch-on-focus. El endpoint puede tardar hasta ~2s por el fan-out
 * a Alpaca data API; staleTime evita re-fetches innecesarios entre re-renders.
 */
export function usePortfolioPositions() {
  return useQuery<PortfolioPositionsResponse, ParsedError>({
    queryKey: ['portfolio', 'positions'],
    queryFn: async () => {
      try {
        return await getPositions();
      } catch (err) {
        throw parseError(err);
      }
    },
    staleTime: 30_000,
    refetchOnWindowFocus: true,
  });
}
