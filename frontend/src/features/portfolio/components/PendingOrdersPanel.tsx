import { Clock } from 'lucide-react';
import { format, parseISO } from 'date-fns';
import { es } from 'date-fns/locale';
import { portfolioMessages } from '@/lib/messages.es';
import type { PendingOrderDto } from '@/types/api';

interface Props {
  orders: PendingOrderDto[];
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
export function PendingOrdersPanel({ orders }: Props) {
  if (orders.length === 0) return null;

  return (
    <details
      open
      className="group rounded-lg border border-slate-200 bg-white shadow-sm"
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
                · {format(parseISO(order.submittedAt), "d MMM yyyy HH:mm", { locale: es })}
                {order.quotedTotal !== null &&
                  ` · ${currencyFormatter.format(Number(order.quotedTotal))}`}
              </span>
            </div>
            <span className="inline-flex w-fit items-center gap-1 rounded-md bg-orange-100 px-2 py-1 text-xs font-medium text-orange-700">
              {portfolioMessages.pendingBadge}
            </span>
          </li>
        ))}
      </ul>
    </details>
  );
}
