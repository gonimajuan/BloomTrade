import { formatDistanceToNow } from 'date-fns';
import { es } from 'date-fns/locale';
import { RefreshCw } from 'lucide-react';
import { formatLocalDateTime } from '@/lib/dateFormat';
import { portfolioMessages } from '@/lib/messages.es';
import { Card } from '@/components/ui/Card';
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
 * Card hero del saldo disponible (SPEC §12.1). Revamp Lote D: glass-elevated + número
 * 4xl tabular-nums + botón refresh subtle.
 */
export function BalanceCard({ data, isLoading, isFetching, onRefresh }: Props) {
  return (
    <Card
      variant="glass-elevated"
      role="region"
      aria-label={portfolioMessages.balanceTitle}
      className="p-8"
    >
      <div className="flex items-start justify-between gap-4">
        <div>
          <h2 className="text-xs font-medium uppercase tracking-[0.2em] text-slate-400">
            {portfolioMessages.balanceTitle}
          </h2>
          {isLoading ? (
            <div className="mt-2 h-9 w-48 animate-pulse rounded-xl bg-white/5" />
          ) : data ? (
            <p className="mt-2 text-4xl font-semibold tabular-nums text-white">
              {currencyFormatter.format(Number(data.balance))}
            </p>
          ) : (
            <p className="mt-2 text-4xl font-semibold text-slate-600">—</p>
          )}
          {data && (
            <p
              className="mt-2 text-xs text-slate-500"
              title={`Última actualización: ${formatLocalDateTime(data.lastUpdatedAt)}`}
            >
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
          className="rounded-xl border border-white/10 bg-slate-800/60 p-2 text-slate-300 backdrop-blur-sm transition-colors hover:bg-slate-700/70 hover:text-white disabled:cursor-not-allowed disabled:opacity-50"
        >
          <RefreshCw
            className={`h-4 w-4 ${isFetching ? 'animate-spin' : ''}`}
            aria-hidden="true"
          />
        </button>
      </div>
    </Card>
  );
}
