import { format, parseISO } from 'date-fns';
import { es } from 'date-fns/locale';
import { Link } from 'react-router-dom';
import { useOrdersRecent } from '@/features/dashboard/hooks/useOrdersRecent';
import { dashboardMessages } from '@/lib/messages.es';
import type { OrderHistoryDto, OrderStatus } from '@/types/api';

const STATUS_PALETTE: Record<OrderStatus, string> = {
  PENDING: 'bg-amber-100 text-amber-800',
  EXECUTED: 'bg-emerald-100 text-emerald-800',
  REJECTED: 'bg-rose-100 text-rose-800',
  FAILED: 'bg-rose-200 text-rose-900',
};

/**
 * Widget "Últimas 10 órdenes" embebido en /dashboard (HU-F17 plan C8).
 * Sin UI de filtros (decisión MVP — filtros via curl). Empty state con CTA a /trade.
 */
export function RecentOrdersWidget() {
  const { data, isLoading, error } = useOrdersRecent();

  if (isLoading) {
    return (
      <section className="rounded-lg border border-slate-200 bg-white p-6 text-sm text-slate-500">
        Cargando últimas órdenes…
      </section>
    );
  }
  if (error) {
    return (
      <section className="rounded-md border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-900">
        No se pudieron cargar las órdenes: {error.message}
      </section>
    );
  }
  if (!data) return null;

  const orders = data.content;
  const isEmpty = orders.length === 0;

  return (
    <section className="rounded-lg border border-slate-200 bg-white shadow-sm">
      <details open className="group">
        <summary className="flex cursor-pointer items-center justify-between rounded-t-lg bg-slate-50 px-6 py-3 text-sm font-medium text-slate-700">
          <span>{dashboardMessages.orders.title}</span>
          <span className="text-xs text-slate-400 transition group-open:rotate-180">▾</span>
        </summary>
        <div className="px-6 py-4">
          {isEmpty ? (
            <div className="flex flex-col items-center gap-2 py-4 text-sm text-slate-500">
              <p>{dashboardMessages.orders.empty}</p>
              <Link
                to="/trade"
                className="rounded-md bg-slate-900 px-3 py-1.5 text-xs font-medium text-white hover:bg-slate-800"
              >
                {dashboardMessages.orders.emptyCta}
              </Link>
            </div>
          ) : (
            <OrdersTable orders={orders} />
          )}
        </div>
      </details>
    </section>
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
        </tr>
      </thead>
      <tbody className="divide-y divide-slate-100">
        {orders.map((order) => (
          <tr key={order.orderId}>
            <td className="py-2 font-mono font-semibold text-slate-900">{order.ticker}</td>
            <td
              className={
                order.side === 'BUY'
                  ? 'py-2 font-medium text-emerald-700'
                  : 'py-2 font-medium text-rose-700'
              }
            >
              {order.side === 'BUY'
                ? dashboardMessages.orders.sideBuy
                : dashboardMessages.orders.sideSell}
            </td>
            <td className="py-2 text-right text-slate-700">{order.quantity}</td>
            <td className="py-2">
              <span
                className={`rounded-full px-2 py-0.5 text-xs font-semibold ${STATUS_PALETTE[order.status]}`}
              >
                {dashboardMessages.orders.status[order.status]}
              </span>
            </td>
            <td className="py-2 text-right text-xs text-slate-500">
              {format(parseISO(order.submittedAt), 'dd-MMM-yyyy HH:mm', { locale: es })}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
