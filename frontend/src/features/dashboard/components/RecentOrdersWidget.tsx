import { Link } from 'react-router-dom';
import { useOrdersRecent } from '@/features/dashboard/hooks/useOrdersRecent';
import { CancelOrderButton } from '@/features/trading/components/CancelOrderButton';
import { formatLocalDateTime } from '@/lib/dateFormat';
import { dashboardMessages } from '@/lib/messages.es';
import type { OrderHistoryDto, OrderStatus } from '@/types/api';
import { Card } from '@/components/ui/Card';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';

type BadgeVariant = 'neutral' | 'success' | 'error' | 'warning' | 'accent';

const STATUS_BADGE_VARIANT: Record<OrderStatus, BadgeVariant> = {
  PENDING: 'warning',
  EXECUTED: 'success',
  REJECTED: 'error',
  FAILED: 'error',
  CANCELED: 'neutral',
  EXPIRED: 'neutral',
};

/**
 * Widget "Últimas 10 órdenes" embebido en /dashboard (HU-F17 plan C8).
 * Revamp Lote D: Card glass + Badge primitives para status + Button para CTA empty.
 */
export function RecentOrdersWidget() {
  const { data, isLoading, error } = useOrdersRecent();

  if (isLoading) {
    return (
      <Card variant="glass" className="p-6 text-sm text-slate-400">
        Cargando últimas órdenes…
      </Card>
    );
  }
  if (error) {
    return (
      <Card
        variant="glass"
        className="border-rose-500/30 bg-rose-500/10 px-4 py-3 text-sm text-rose-200"
      >
        <p>No se pudieron cargar las órdenes: {error.message}</p>
        <p className="mt-1 text-xs italic text-rose-300/80">
          Código: {error.code}
          {error.traceId && ` · traceId: ${error.traceId}`}
        </p>
      </Card>
    );
  }
  if (!data) return null;

  const orders = data.content;
  const isEmpty = orders.length === 0;

  return (
    <Card variant="glass" className="overflow-hidden">
      <details open className="group">
        <summary className="flex cursor-pointer items-center justify-between border-b border-white/10 bg-slate-800/40 px-6 py-3 text-sm font-medium text-slate-200 transition-colors hover:bg-slate-800/60">
          <span>{dashboardMessages.orders.title}</span>
          <span className="text-xs text-slate-400 transition-transform group-open:rotate-180">
            ▾
          </span>
        </summary>
        <div className="px-6 py-4">
          {isEmpty ? (
            <div className="flex flex-col items-center gap-3 py-6 text-sm text-slate-400">
              <p>{dashboardMessages.orders.empty}</p>
              <Link to="/trade">
                <Button variant="primary" size="sm">
                  {dashboardMessages.orders.emptyCta}
                </Button>
              </Link>
            </div>
          ) : (
            <OrdersTable orders={orders} />
          )}
        </div>
      </details>
    </Card>
  );
}

function OrdersTable({ orders }: { orders: OrderHistoryDto[] }) {
  const h = dashboardMessages.orders.tableHeaders;
  return (
    <table className="w-full text-sm">
      <thead className="text-left text-xs font-medium uppercase tracking-wider text-slate-500">
        <tr>
          <th scope="col" className="pb-2">
            {h.ticker}
          </th>
          <th scope="col" className="pb-2">
            {h.side}
          </th>
          <th scope="col" className="pb-2 text-right">
            {h.quantity}
          </th>
          <th scope="col" className="pb-2">
            {h.status}
          </th>
          <th scope="col" className="pb-2 text-right">
            {h.date}
          </th>
          <th scope="col" className="pb-2 text-right">
            {h.actions}
          </th>
        </tr>
      </thead>
      <tbody className="divide-y divide-white/5">
        {orders.map((order) => (
          <tr key={order.orderId} className="transition-colors hover:bg-white/5">
            <td className="py-2.5 font-mono font-semibold text-white">
              {order.ticker}
            </td>
            <td
              className={
                order.side === 'BUY'
                  ? 'py-2.5 font-medium text-emerald-300'
                  : 'py-2.5 font-medium text-rose-300'
              }
            >
              {order.side === 'BUY'
                ? dashboardMessages.orders.sideBuy
                : dashboardMessages.orders.sideSell}
            </td>
            <td className="py-2.5 text-right tabular-nums text-slate-300">
              {order.quantity}
            </td>
            <td className="py-2.5">
              <Badge variant={STATUS_BADGE_VARIANT[order.status]}>
                {dashboardMessages.orders.status[order.status]}
              </Badge>
            </td>
            <td className="py-2.5 text-right text-xs text-slate-500">
              {formatLocalDateTime(order.submittedAt)}
            </td>
            <td className="py-2.5 text-right">
              {order.status === 'PENDING' && order.alpacaOrderId && (
                <CancelOrderButton
                  orderId={order.orderId}
                  side={order.side}
                  quantity={order.quantity}
                  ticker={order.ticker}
                />
              )}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
