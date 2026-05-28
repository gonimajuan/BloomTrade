import { TrendingDown, TrendingUp } from 'lucide-react';
import { cn } from '@/lib/cn';
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
  selected: boolean;
  onSelect: (ticker: string) => void;
}

/**
 * Fila clickeable de un ticker en el grid del dashboard.
 * Revamp Lote D: dark glass + selected state violet glow.
 */
export function TickerRow({ item, selected, onSelect }: Props) {
  const pct = item.dayChangePct !== null ? Number.parseFloat(item.dayChangePct) : null;
  const positive = pct !== null && pct > 0;
  const negative = pct !== null && pct < 0;
  const colorClass =
    pct === null
      ? 'text-slate-500'
      : positive
        ? 'text-emerald-400'
        : negative
          ? 'text-rose-400'
          : 'text-slate-400';
  const Icon = positive ? TrendingUp : negative ? TrendingDown : null;

  const priceDisplay =
    item.currentPrice !== null
      ? currencyFmt.format(Number.parseFloat(item.currentPrice))
      : '—';
  const pctDisplay =
    pct !== null ? `${pct > 0 ? '+' : ''}${percentFmt.format(pct)}%` : '—';

  return (
    <button
      type="button"
      onClick={() => onSelect(item.ticker)}
      aria-pressed={selected}
      className={cn(
        'grid w-full grid-cols-[60px_minmax(0,1fr)_70px] items-center gap-2 rounded-lg px-2 py-1.5 text-sm transition-all',
        selected
          ? 'bg-violet-500/15 text-white shadow-glow-violet-sm'
          : 'text-slate-300 hover:bg-white/5 hover:text-white',
      )}
    >
      <span
        className={cn(
          'font-mono font-semibold',
          selected ? 'text-violet-200' : 'text-white',
        )}
      >
        {item.ticker}
      </span>
      <span
        className={cn(
          'truncate text-right tabular-nums',
          selected ? 'text-slate-100' : 'text-slate-300',
        )}
      >
        {priceDisplay}
      </span>
      <span
        className={cn(
          'flex items-center justify-end gap-1 font-medium tabular-nums',
          selected ? 'text-slate-100' : colorClass,
        )}
      >
        {Icon && <Icon className="h-3 w-3" aria-hidden="true" />}
        <span>{pctDisplay}</span>
      </span>
    </button>
  );
}
