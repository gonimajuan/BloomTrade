import { Clock } from 'lucide-react';
import { CancelOrderButton } from '@/features/trading/components/CancelOrderButton';
import { formatLocalDateTime } from '@/lib/dateFormat';
import { portfolioMessages } from '@/lib/messages.es';
import { Card } from '@/components/ui/Card';
import { Badge } from '@/components/ui/Badge';
import { cn } from '@/lib/cn';
import type { PendingOrderDto } from '@/types/api';

interface Props {
  orders: PendingOrderDto[];
  isFetching?: boolean;
}

const currencyFormatter = new Intl.NumberFormat('es-CO', {
  style: 'currency',
  currency: 'USD',
  minimumFractionDigits: 2,
});

/**
 * Panel colapsable de órdenes encoladas en Alpaca (SPEC §12.1 + plan C4).
 * Revamp Lote D: Card glass + summary glass + Badge warning para estado encolada.
 */
export function PendingOrdersPanel({ orders, isFetching = false }: Props) {
  if (orders.length === 0) return null;

  return (
    <Card
      variant="glass"
      aria-busy={isFetching}
      className={cn('overflow-hidden transition-opacity', isFetching && 'opacity-60')}
    >
      <details open className="group">
        <summary className="flex cursor-pointer items-center gap-2 border-b border-white/10 bg-slate-800/40 px-4 py-3 text-sm font-medium text-slate-200 transition-colors hover:bg-slate-800/60">
          <Clock className="h-4 w-4 text-amber-300" aria-hidden="true" />
          <span>
            {portfolioMessages.pendingSection.replace('{n}', String(orders.length))}
          </span>
          <span className="ml-auto text-xs text-slate-400 transition-transform group-open:rotate-180">
            ▾
          </span>
        </summary>
        <ul className="divide-y divide-white/5">
          {orders.map((order) => (
            <li
              key={order.orderId}
              className="flex items-center justify-between gap-3 px-4 py-3"
            >
              <div className="text-sm text-slate-200">
                <span className="font-medium text-white">
                  {order.side === 'BUY' ? 'Compra' : 'Venta'} de {order.quantity}{' '}
                  {order.ticker}
                </span>
                <span className="ml-2 text-xs text-slate-500">
                  · {formatLocalDateTime(order.submittedAt)}
                  {order.quotedTotal !== null &&
                    ` · ${currencyFormatter.format(Number(order.quotedTotal))}`}
                </span>
              </div>
              <div className="flex items-center gap-2">
                <Badge variant="warning">{portfolioMessages.pendingBadge}</Badge>
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
    </Card>
  );
}
