import { formatDistanceToNow } from 'date-fns';
import { es } from 'date-fns/locale';
import { RefreshCw } from 'lucide-react';
import { portfolioMessages } from '@/lib/messages.es';
import type { BalanceResponse } from '@/types/api';

interface Props {
  data: BalanceResponse | undefined;
  isLoading: boolean;
  isFetching: boolean;
  onRefresh: () => void;
}

const currencyFormatter = new Intl.NumberFormat('es-CO', {
  style: 'currency',
  currency: 'USD',
  minimumFractionDigits: 2,
});

/**
 * Card del saldo disponible (SPEC §12.1, plan D6 + D8). Formato es-CO de moneda y label
 * de actualización relativa "hace Xs". Botón de refresh manual invalida la query desde
 * el padre.
 */
export function BalanceCard({ data, isLoading, isFetching, onRefresh }: Props) {
  return (
    <section
      aria-label={portfolioMessages.balanceTitle}
      className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm"
    >
      <div className="flex items-start justify-between gap-4">
        <div>
          <h2 className="text-sm font-medium uppercase tracking-wide text-slate-500">
            {portfolioMessages.balanceTitle}
          </h2>
          {isLoading ? (
            <div className="mt-2 h-9 w-48 animate-pulse rounded bg-slate-100" />
          ) : data ? (
            <p className="mt-2 text-3xl font-semibold text-slate-900">
              {currencyFormatter.format(Number(data.balance))}
            </p>
          ) : (
            <p className="mt-2 text-3xl font-semibold text-slate-400">—</p>
          )}
          {data && (
            <p className="mt-1 text-xs text-slate-500">
              Actualizado{' '}
              {formatDistanceToNow(new Date(data.lastUpdatedAt), {
                addSuffix: true,
                locale: es,
              })}
            </p>
          )}
        </div>
        <button
          type="button"
          onClick={onRefresh}
          disabled={isFetching}
          aria-label={portfolioMessages.refreshAria}
          className="rounded-md border border-slate-300 p-2 text-slate-600 transition hover:border-slate-500 hover:text-slate-900 disabled:cursor-not-allowed disabled:opacity-50"
        >
          <RefreshCw className={`h-4 w-4 ${isFetching ? 'animate-spin' : ''}`} aria-hidden="true" />
        </button>
      </div>
    </section>
  );
}
