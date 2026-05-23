import { useMutation } from '@tanstack/react-query';
import {
  placeOrder,
  type PlaceOrderResult,
} from '@/features/trading/api/tradingApi';
import { parseError, type ParsedError } from '@/lib/errorParser';
import type { OrderSide, OrderType } from '@/types/api';

export interface SubmitOrderInput {
  ticker: string;
  side: OrderSide;
  type: OrderType;
  quantity: number;
}

/**
 * Mutación POST /orders. Genera `clientOrderId = crypto.randomUUID()` dentro
 * del `mutationFn` (SPEC §12.1 paso 5). Cada `mutate(...)` produce un UUID nuevo,
 * por lo que el hook + el botón deshabilitado durante `isPending` previenen el
 * doble-submit por click; la idempotencia del backend cubre el caso de retry
 * de red del mismo clientOrderId (200 vs 201).
 *
 * <p>Error codes esperados (SPEC §6.1.2):
 * INVALID_TICKER · INVALID_QUANTITY · INVALID_SIDE · SIDE_NOT_YET_IMPLEMENTED ·
 * INVALID_CLIENT_ORDER_ID · INSUFFICIENT_FUNDS · ALPACA_ORDER_REJECTED ·
 * ALPACA_API_ERROR · MARKET_DATA_UNAVAILABLE · ACCOUNT_NOT_ACTIVE · MARKET_CLOSED.
 */
export function useSubmitOrder() {
  return useMutation<PlaceOrderResult, ParsedError, SubmitOrderInput>({
    mutationFn: async (input) => {
      try {
        return await placeOrder({
          clientOrderId: crypto.randomUUID(),
          ticker: input.ticker,
          side: input.side,
          type: input.type,
          quantity: input.quantity,
        });
      } catch (err) {
        throw parseError(err);
      }
    },
  });
}
