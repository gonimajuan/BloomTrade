import { useMutation, useQueryClient } from '@tanstack/react-query';
import { cancelOrder } from '@/features/trading/api/tradingApi';
import { parseError, type ParsedError } from '@/lib/errorParser';
import type { OrderResponse } from '@/types/api';

/**
 * HU-F15 — mutación para cancelar una orden Market en cola.
 *
 * <p>Idempotente por order.id en el backend (D7). Tras success, invalida queries de las 3
 * superficies que muestran datos afectados (D21 D-FRONTEND-INVALIDATION-STRATEGY):
 * <ul>
 *   <li>{@code balance} — refund BUY actualiza el saldo</li>
 *   <li>{@code positions} (incluye pendingOrders) — restore SELL re-INSERT posición</li>
 *   <li>{@code recentOrders} — el widget dashboard muestra el nuevo status</li>
 * </ul>
 *
 * <p>Outcomes del body distinguibles por el caller (CancelOrderButton):
 * <ul>
 *   <li>{@code status="CANCELED"} → polling-OK ({@code refundedAmount} BUY o {@code restoredQty} SELL).</li>
 *   <li>{@code status="PENDING" && cancelRequestedAt} → polling-timeout (reconcile materializará).</li>
 *   <li>{@code status="EXECUTED"} → race-filled (la orden se ejecutó antes del cancel).</li>
 * </ul>
 */
export function useCancelOrder() {
  const queryClient = useQueryClient();
  return useMutation<OrderResponse, ParsedError, string>({
    mutationFn: async (orderId: string) => {
      try {
        return await cancelOrder(orderId);
      } catch (err) {
        throw parseError(err);
      }
    },
    onSuccess: () => {
      // Invalidación granular (D21): solo las queries afectadas, no `refetchType: 'all'`.
      queryClient.invalidateQueries({ queryKey: ['balance'] });
      queryClient.invalidateQueries({ queryKey: ['positions'] });
      queryClient.invalidateQueries({ queryKey: ['recentOrders'] });
      queryClient.invalidateQueries({ queryKey: ['orders'] }); // HU-F17 history
    },
  });
}

/**
 * Helper para construir el mensaje de éxito post-cancel, side-aware.
 * BUY → "Orden cancelada — USD X restaurados a tu saldo"
 * SELL → "Orden cancelada — N acciones restauradas a tu posición"
 */
export function buildCancelSuccessMessage(order: OrderResponse): string {
  if (order.status === 'CANCELED' && order.refundedAmount != null) {
    return `Orden cancelada — USD ${order.refundedAmount} restaurados a tu saldo`;
  }
  if (order.status === 'CANCELED' && order.restoredQty != null) {
    return `Orden cancelada — ${order.restoredQty} ${order.ticker} restaurados a tu posición`;
  }
  if (order.status === 'PENDING' && order.cancelRequestedAt != null) {
    return 'Cancelación en proceso. Verificaremos en unos segundos.';
  }
  if (order.status === 'EXECUTED') {
    return 'Tu orden se ejecutó antes de que llegara la cancelación. La cancelación no fue aplicada.';
  }
  return 'Operación completada.';
}
