import { useQuery } from '@tanstack/react-query';
import { getBalance } from '@/features/portfolio/api/portfolioApi';
import { parseError, type ParsedError } from '@/lib/errorParser';
import type { BalanceResponse } from '@/types/api';

/**
 * GET /portfolio/balance (HU-F21). Plan D8: stale 30s + refetch-on-focus para refrescar
 * tras operar en /trade y volver. Sin polling intervalado.
 */
export function useBalance() {
  return useQuery<BalanceResponse, ParsedError>({
    queryKey: ['portfolio', 'balance'],
    queryFn: async () => {
      try {
        return await getBalance();
      } catch (err) {
        throw parseError(err);
      }
    },
    staleTime: 30_000,
    refetchOnWindowFocus: true,
  });
}
