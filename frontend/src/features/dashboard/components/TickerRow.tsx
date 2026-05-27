import { TrendingDown, TrendingUp } from 'lucide-react';
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
 * Fila clickeable de un ticker en el grid del dashboard (Día 10 polish).
 * El sparkline mini se eliminó: ahora cada fila es un botón que selecciona el ticker
 * y la gráfica grande se muestra en {@link SparklinePanel} debajo del grid.
 */
export function TickerRow({ item, selected, onSelect }: Props) {
  const pct = item.dayChangePct !== null ? Number.parseFloat(item.dayChangePct) : null;
  const positive = pct !== null && pct > 0;
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
    <button
      type="button"
      onClick={() => onSelect(item.ticker)}
      aria-pressed={selected}
      className={`grid w-full grid-cols-[60px_minmax(0,1fr)_70px] items-center gap-2 rounded px-2 py-1.5 text-sm transition ${
        selected
          ? 'bg-slate-900 text-white hover:bg-slate-800'
          : 'hover:bg-slate-100'
      }`}
    >
      <span
        className={`font-mono font-semibold ${selected ? 'text-white' : 'text-slate-900'}`}
      >
        {item.ticker}
      </span>
      <span
        className={`truncate text-right ${selected ? 'text-slate-100' : 'text-slate-700'}`}
      >
        {priceDisplay}
      </span>
      <span
        className={`flex items-center justify-end gap-1 font-medium ${
          selected ? 'text-white' : colorClass
        }`}
      >
        {Icon && <Icon className="h-3 w-3" aria-hidden="true" />}
        <span>{pctDisplay}</span>
      </span>
    </button>
  );
}
