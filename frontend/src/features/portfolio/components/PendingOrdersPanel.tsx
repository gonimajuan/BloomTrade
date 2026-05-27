import { Clock } from 'lucide-react';
import { CancelOrderButton } from '@/features/trading/components/CancelOrderButton';
import { formatLocalDateTime } from '@/lib/dateFormat';
import { portfolioMessages } from '@/lib/messages.es';
import type { PendingOrderDto } from '@/types/api';

interface Props {
  orders: PendingOrderDto[];
  /** P1-2 audit: dim el panel mientras hay refetch en vuelo con data previa visible. */
  isFetching?: boolean;
}

const currencyFormatter = new Intl.NumberFormat('es-CO', {
  style: 'currency',
  currency: 'USD',
  minimumFractionDigits: 2,
});

/**
 * Panel colapsable de órdenes encoladas en Alpaca (SPEC §12.1 + plan C4).
 * Abierto por default si hay órdenes (sirve como notificación). Badge naranja
 * "Esperando apertura de mercado".
 */
export function PendingOrdersPanel({ orders, isFetching = false }: Props) {
  if (orders.length === 0) return null;

  return (
    <details
      open
      aria-busy={isFetching}
      className={`group rounded-lg border border-slate-200 bg-white shadow-sm transition-opacity ${
        isFetching ? 'opacity-60' : ''
      }`}
    >
      <summary className="flex cursor-pointer items-center gap-2 px-4 py-3 text-sm font-medium text-slate-700 hover:bg-slate-50">
        <Clock className="h-4 w-4 text-orange-600" aria-hidden="true" />
        <span>
          {portfolioMessages.pendingSection.replace('{n}', String(orders.length))}
        </span>
      </summary>
      <ul className="divide-y divide-slate-100 border-t border-slate-100">
        {orders.map((order) => (
          <li
            key={order.orderId}
            className="flex flex-col gap-2 px-4 py-3 sm:flex-row sm:items-center sm:justify-between"
          >
            <div className="text-sm text-slate-700">
              <span className="font-medium text-slate-900">
                {order.side === 'BUY' ? 'Compra' : 'Venta'} de {order.quantity} {order.ticker}
              </span>
              <span className="ml-2 text-xs text-slate-500">
                · {formatLocalDateTime(order.submittedAt)}
                {order.quotedTotal !== null &&
                  ` · ${currencyFormatter.format(Number(order.quotedTotal))}`}
              </span>
            </div>
            <div className="flex items-center gap-2">
              <span className="inline-flex w-fit items-center gap-1 rounded-md bg-orange-100 px-2 py-1 text-xs font-medium text-orange-700">
                {portfolioMessages.pendingBadge}
              </span>
              <CancelOrderButton
                orderId={order.orderId}
                side={order.side}
                quantity={order.quantity}
                ticker={order.ticker}
                quotedTotal={order.quotedTotal}
                cancelRequestedAt={order.cancelRequestedAt}
              />
            </div>
          </li>
        ))}
      </ul>
    </details>
  );
}
