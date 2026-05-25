import { TrendingDown, TrendingUp } from 'lucide-react';
import { Sparkline } from '@/features/dashboard/components/Sparkline';
import type { TickerDashboardDto } from '@/types/api';

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
  item: TickerDashboardDto;
}

/**
 * Fila de un ticker individual en el grid del dashboard (HU-F18 §12.1 SPEC).
 * Layout horizontal compacto: ticker | precio | Δ% color-coded | sparkline.
 */
export function TickerRow({ item }: Props) {
  const pct = item.dayChangePct !== null ? Number.parseFloat(item.dayChangePct) : null;
  const positive = pct === null ? null : pct > 0;
  const negative = pct !== null && pct < 0;
  const colorClass =
    pct === null
      ? 'text-slate-400'
      : positive
        ? 'text-emerald-600'
        : negative
          ? 'text-rose-600'
          : 'text-slate-500';
  const Icon = positive ? TrendingUp : negative ? TrendingDown : null;

  const priceDisplay =
    item.currentPrice !== null
      ? currencyFmt.format(Number.parseFloat(item.currentPrice))
      : '—';
  const pctDisplay =
    pct !== null ? `${pct > 0 ? '+' : ''}${percentFmt.format(pct)}%` : '—';

  return (
    <div className="grid grid-cols-[80px_minmax(0,1fr)_70px_100px] items-center gap-2 py-1.5 text-sm">
      <span className="font-mono font-semibold text-slate-900">{item.ticker}</span>
      <span className="truncate text-right text-slate-700">{priceDisplay}</span>
      <span className={`flex items-center justify-end gap-1 font-medium ${colorClass}`}>
        {Icon && <Icon className="h-3 w-3" aria-hidden="true" />}
        <span>{pctDisplay}</span>
      </span>
      <div className="flex justify-end">
        <Sparkline data={item.sparkline} positive={positive} />
      </div>
    </div>
  );
}
