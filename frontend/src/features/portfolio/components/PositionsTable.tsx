import { Link } from 'react-router-dom';
import { TrendingDown, TrendingUp } from 'lucide-react';
import { portfolioMessages } from '@/lib/messages.es';
import { Card } from '@/components/ui/Card';
import { Button } from '@/components/ui/Button';
import { cn } from '@/lib/cn';
import type { PositionDto } from '@/types/api';

interface Props {
  positions: PositionDto[];
  isFetching?: boolean;
}

const currencyFormatter = new Intl.NumberFormat('es-CO', {
  style: 'currency',
  currency: 'USD',
  minimumFractionDigits: 2,
});

function formatMoneyOrDash(value: string | null): string {
  return value === null ? '—' : currencyFormatter.format(Number(value));
}

function pnlClass(pnl: string | null): string {
  if (pnl === null) return 'text-slate-500';
  const n = Number(pnl);
  if (n > 0) return 'text-emerald-400';
  if (n < 0) return 'text-rose-400';
  return 'text-slate-500';
}

function PnLCell({ pnl, pnlPct }: { pnl: string | null; pnlPct: string | null }) {
  if (pnl === null || pnlPct === null) {
    return <span className="text-slate-500">—</span>;
  }
  const n = Number(pnl);
  const Icon = n > 0 ? TrendingUp : n < 0 ? TrendingDown : null;
  return (
    <span className={`inline-flex items-center gap-1 font-medium tabular-nums ${pnlClass(pnl)}`}>
      {Icon && <Icon className="h-4 w-4" aria-hidden="true" />}
      {currencyFormatter.format(n)} ({Number(pnlPct).toFixed(2)}%)
    </span>
  );
}

/**
 * Tabla de posiciones con P&L color-coded e icono ▲/▼ (a11y daltonismo).
 * Revamp Lote D: Card glass + hover white/5 + empty state con Button primary.
 */
export function PositionsTable({ positions, isFetching = false }: Props) {
  if (positions.length === 0) {
    return (
      <Card variant="glass-outline" className="border-dashed p-10 text-center">
        <p className="text-sm text-slate-300">{portfolioMessages.emptyState}</p>
        <Link to="/trade" className="mt-4 inline-block">
          <Button variant="primary" size="md">
            {portfolioMessages.emptyCta}
          </Button>
        </Link>
      </Card>
    );
  }

  return (
    <Card
      variant="glass"
      aria-busy={isFetching}
      className={cn(
        'overflow-hidden transition-opacity',
        isFetching && 'opacity-60',
      )}
    >
      <table className="w-full divide-y divide-white/10 text-sm">
        <thead className="bg-slate-800/40 text-xs uppercase tracking-wider text-slate-400">
          <tr>
            <th scope="col" className="px-4 py-3 text-left">
              Ticker
            </th>
            <th scope="col" className="px-4 py-3 text-right">
              Cant.
            </th>
            <th scope="col" className="px-4 py-3 text-right">
              Costo prom.
            </th>
            <th scope="col" className="px-4 py-3 text-right">
              Actual
            </th>
            <th scope="col" className="px-4 py-3 text-right">
              Valor
            </th>
            <th
              scope="col"
              className="px-4 py-3 text-right"
              title={portfolioMessages.pnlTooltip}
            >
              P&amp;L
            </th>
          </tr>
        </thead>
        <tbody className="divide-y divide-white/5">
          {positions.map((p) => (
            <tr key={p.ticker} className="transition-colors hover:bg-white/5">
              <td className="px-4 py-3 font-mono font-semibold text-white">
                {p.ticker}
              </td>
              <td className="px-4 py-3 text-right tabular-nums text-slate-300">
                {p.quantity}
              </td>
              <td className="px-4 py-3 text-right tabular-nums text-slate-300">
                {currencyFormatter.format(Number(p.avgCost))}
              </td>
              <td className="px-4 py-3 text-right tabular-nums text-slate-300">
                {formatMoneyOrDash(p.currentPrice)}
              </td>
              <td className="px-4 py-3 text-right tabular-nums text-slate-300">
                {formatMoneyOrDash(p.marketValue)}
              </td>
              <td className="px-4 py-3 text-right">
                <PnLCell pnl={p.unrealizedPnL} pnlPct={p.unrealizedPnLPct} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </Card>
  );
}
