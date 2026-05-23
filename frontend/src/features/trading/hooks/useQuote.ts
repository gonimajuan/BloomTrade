import { useMutation } from '@tanstack/react-query';
import { requestQuote } from '@/features/trading/api/tradingApi';
import { parseError, type ParsedError } from '@/lib/errorParser';
import type { QuoteRequest, QuoteResponse } from '@/types/api';

/**
 * Mutación POST /orders/quote (SPEC §12.1 paso 3). No persiste nada; el botón
 * "Obtener quote" del OrderForm la dispara y la respuesta se pasa al OrderQuotePanel.
 *
 * <p>Error codes esperados (SPEC §6.1.1):
 * INVALID_TICKER · INVALID_QUANTITY · MARKET_DATA_UNAVAILABLE ·
 * ACCOUNT_NOT_ACTIVE · SIDE_NOT_YET_IMPLEMENTED · AUTHENTICATION_REQUIRED.
 */
export function useQuote() {
  return useMutation<QuoteResponse, ParsedError, QuoteRequest>({
    mutationFn: async (input) => {
      try {
        return await requestQuote(input);
      } catch (err) {
        throw parseError(err);
      }
    },
  });
}
