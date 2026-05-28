import { formatDistanceToNow } from 'date-fns';
import { es } from 'date-fns/locale';
import { RefreshCw, TrendingDown, TrendingUp } from 'lucide-react';
import { Card } from '@/components/ui/Card';
import { Badge } from '@/components/ui/Badge';
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

type PnLVariant = 'success' | 'error' | 'neutral';

/**
 * Card hero del dashboard con equity total + P&L no realizado (HU-F18 plan C7).
 * Revamp Lote D: glass-elevated + número 4xl tabular-nums + Badge variant para P&L.
 */
export function EquityCard({ equity, fetchedAt, onRefresh, isFetching }: Props) {
  const balance = Number.parseFloat(equity.balance);
  const equityVal = equity.equity !== null ? Number.parseFloat(equity.equity) : null;
  const pnl = equity.unrealizedPnL !== null ? Number.parseFloat(equity.unrealizedPnL) : null;
  const pnlPct =
    equity.unrealizedPnLPct !== null ? Number.parseFloat(equity.unrealizedPnLPct) : null;

  const headlineAmount = equityVal !== null ? equityVal : balance;
  const showEquityCaveat = equityVal === null;

  let pnlBadge: { text: string; variant: PnLVariant; Icon: typeof TrendingUp | null } | null =
    null;
  if (pnl !== null && pnlPct !== null) {
    if (pnl > 0) {
      pnlBadge = {
        text: dashboardMessages.equity.pnlPositive(
          currencyFmt.format(Math.abs(pnl)),
          `${percentFmt.format(pnlPct)}%`,
        ),
        variant: 'success',
        Icon: TrendingUp,
      };
    } else if (pnl < 0) {
      pnlBadge = {
        text: dashboardMessages.equity.pnlNegative(
          currencyFmt.format(Math.abs(pnl)),
          `${percentFmt.format(pnlPct)}%`,
        ),
        variant: 'error',
        Icon: TrendingDown,
      };
    } else {
      pnlBadge = {
        text: dashboardMessages.equity.pnlNeutral,
        variant: 'neutral',
        Icon: null,
      };
    }
  } else if (equity.costBasisTotal !== null && Number.parseFloat(equity.costBasisTotal) === 0) {
    pnlBadge = {
      text: dashboardMessages.equity.pnlNeutral,
      variant: 'neutral',
      Icon: null,
    };
  }

  return (
    <Card variant="glass-elevated" className="p-8">
      <div className="flex items-start justify-between gap-4">
        <div className="space-y-2">
          <h2 className="text-xs font-medium uppercase tracking-[0.2em] text-slate-400">
            {dashboardMessages.equity.headline}
          </h2>
          <p className="text-4xl font-semibold tabular-nums text-white">
            {currencyFmt.format(headlineAmount)}
          </p>
          {showEquityCaveat && (
            <p className="text-xs text-amber-300">
              {dashboardMessages.equity.withoutPrices(currencyFmt.format(balance))}
            </p>
          )}
          {pnlBadge && (
            <Badge variant={pnlBadge.variant} className="mt-1">
              {pnlBadge.Icon && (
                <pnlBadge.Icon className="h-3.5 w-3.5" aria-hidden="true" />
              )}
              {pnlBadge.text}
            </Badge>
          )}
          <p className="pt-3 text-xs text-slate-500">
            Actualizado hace{' '}
            {formatDistanceToNow(new Date(fetchedAt), { locale: es, addSuffix: false })}
          </p>
        </div>
        <button
          type="button"
          onClick={onRefresh}
          disabled={isFetching}
          aria-label={dashboardMessages.refreshAria}
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
