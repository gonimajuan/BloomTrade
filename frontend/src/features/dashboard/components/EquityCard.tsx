import { formatDistanceToNow } from 'date-fns';
import { es } from 'date-fns/locale';
import { RefreshCw, TrendingDown, TrendingUp } from 'lucide-react';
import { dashboardMessages } from '@/lib/messages.es';
import type { AccountEquityDto } from '@/types/api';

const currencyFmt = new Intl.NumberFormat('es-CO', {
  style: 'currency',
  currency: 'USD',
  minimumFractionDigits: 2,
});

const percentFmt = new Intl.NumberFormat('es-CO', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

interface Props {
  equity: AccountEquityDto;
  fetchedAt: string;
  onRefresh: () => void;
  isFetching: boolean;
}

/**
 * Card superior del dashboard con equity total y P&L no realizado (HU-F18 plan C7).
 *
 * Render condicional:
 *  - Si `equity.equity` está poblado → muestra equity como número principal.
 *  - Si null (Alpaca caído totalmente) → muestra solo balance + texto explicativo.
 *  - Si hay P&L no realizado, lo muestra con signo + ícono + color.
 */
export function EquityCard({ equity, fetchedAt, onRefresh, isFetching }: Props) {
  const balance = Number.parseFloat(equity.balance);
  const equityVal = equity.equity !== null ? Number.parseFloat(equity.equity) : null;
  const pnl = equity.unrealizedPnL !== null ? Number.parseFloat(equity.unrealizedPnL) : null;
  const pnlPct =
    equity.unrealizedPnLPct !== null ? Number.parseFloat(equity.unrealizedPnLPct) : null;

  const headlineAmount = equityVal !== null ? equityVal : balance;
  const showEquityCaveat = equityVal === null;

  let pnlDisplay: { text: string; colorClass: string; Icon: typeof TrendingUp | null } | null =
    null;
  if (pnl !== null && pnlPct !== null) {
    if (pnl > 0) {
      pnlDisplay = {
        text: dashboardMessages.equity.pnlPositive(
          currencyFmt.format(Math.abs(pnl)),
          `${percentFmt.format(pnlPct)}%`,
        ),
        colorClass: 'text-emerald-600',
        Icon: TrendingUp,
      };
    } else if (pnl < 0) {
      pnlDisplay = {
        text: dashboardMessages.equity.pnlNegative(
          currencyFmt.format(Math.abs(pnl)),
          `${percentFmt.format(pnlPct)}%`,
        ),
        colorClass: 'text-rose-600',
        Icon: TrendingDown,
      };
    } else {
      pnlDisplay = {
        text: dashboardMessages.equity.pnlNeutral,
        colorClass: 'text-slate-500',
        Icon: null,
      };
    }
  } else if (equity.costBasisTotal !== null && Number.parseFloat(equity.costBasisTotal) === 0) {
    // Sin posiciones — render explícito para evitar campo en blanco.
    pnlDisplay = {
      text: dashboardMessages.equity.pnlNeutral,
      colorClass: 'text-slate-500',
      Icon: null,
    };
  }

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
      <div className="flex items-start justify-between">
        <div className="space-y-1">
          <h2 className="text-sm font-medium uppercase tracking-wider text-slate-500">
            {dashboardMessages.equity.headline}
          </h2>
          <p className="text-3xl font-bold text-slate-900">
            {currencyFmt.format(headlineAmount)}
          </p>
          {showEquityCaveat && (
            <p className="text-xs text-amber-700">
              {dashboardMessages.equity.withoutPrices(currencyFmt.format(balance))}
            </p>
          )}
          {pnlDisplay && (
            <p
              className={`mt-2 flex items-center gap-1 text-sm font-medium ${pnlDisplay.colorClass}`}
            >
              {pnlDisplay.Icon && <pnlDisplay.Icon className="h-4 w-4" aria-hidden="true" />}
              <span>{pnlDisplay.text}</span>
            </p>
          )}
          <p className="pt-2 text-xs text-slate-400">
            Actualizado hace{' '}
            {formatDistanceToNow(new Date(fetchedAt), { locale: es, addSuffix: false })}
          </p>
        </div>
        <button
          type="button"
          onClick={onRefresh}
          disabled={isFetching}
          className="rounded-md border border-slate-200 p-2 text-slate-600 transition hover:border-slate-300 hover:bg-slate-50 disabled:opacity-50"
          aria-label={dashboardMessages.refreshAria}
        >
          <RefreshCw className={`h-4 w-4 ${isFetching ? 'animate-spin' : ''}`} />
        </button>
      </div>
    </section>
  );
}
