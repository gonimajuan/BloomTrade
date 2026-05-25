import { Link } from 'react-router-dom';
import { TrendingDown, TrendingUp } from 'lucide-react';
import { portfolioMessages } from '@/lib/messages.es';
import type { PositionDto } from '@/types/api';

interface Props {
  positions: PositionDto[];
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
  if (n > 0) return 'text-emerald-600';
  if (n < 0) return 'text-rose-600';
  return 'text-slate-500';
}

function PnLCell({ pnl, pnlPct }: { pnl: string | null; pnlPct: string | null }) {
  if (pnl === null || pnlPct === null) {
    return <span className="text-slate-500">—</span>;
  }
  const n = Number(pnl);
  const Icon = n > 0 ? TrendingUp : n < 0 ? TrendingDown : null;
  return (
    <span className={`inline-flex items-center gap-1 font-medium ${pnlClass(pnl)}`}>
      {Icon && <Icon className="h-4 w-4" aria-hidden="true" />}
      {currencyFormatter.format(n)} ({Number(pnlPct).toFixed(2)}%)
    </span>
  );
}

/**
 * Tabla de posiciones con P&L color-coded e icono ▲/▼ (a11y daltonismo).
 * SPEC §12.1 + plan D7. Empty state inline con CTA a /trade.
 */
export function PositionsTable({ positions }: Props) {
  if (positions.length === 0) {
    return (
      <div className="rounded-lg border border-dashed border-slate-300 bg-white p-10 text-center">
        <p className="text-sm text-slate-600">{portfolioMessages.emptyState}</p>
        <Link
          to="/trade"
          className="mt-4 inline-block rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white transition hover:bg-slate-700"
        >
          {portfolioMessages.emptyCta}
        </Link>
      </div>
    );
  }

  return (
    <div className="overflow-hidden rounded-lg border border-slate-200 bg-white shadow-sm">
      <table className="w-full divide-y divide-slate-200 text-sm">
        <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
          <tr>
            <th scope="col" className="px-4 py-3 text-left">Ticker</th>
            <th scope="col" className="px-4 py-3 text-right">Cant.</th>
            <th scope="col" className="px-4 py-3 text-right">Costo prom.</th>
            <th scope="col" className="px-4 py-3 text-right">Actual</th>
            <th scope="col" className="px-4 py-3 text-right">Valor</th>
            <th
              scope="col"
              className="px-4 py-3 text-right"
              title={portfolioMessages.pnlTooltip}
            >
              P&amp;L
            </th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {positions.map((p) => (
            <tr key={p.ticker} className="hover:bg-slate-50">
              <td className="px-4 py-3 font-semibold text-slate-900">{p.ticker}</td>
              <td className="px-4 py-3 text-right text-slate-700">{p.quantity}</td>
              <td className="px-4 py-3 text-right text-slate-700">
                {currencyFormatter.format(Number(p.avgCost))}
              </td>
              <td className="px-4 py-3 text-right text-slate-700">
                {formatMoneyOrDash(p.currentPrice)}
              </td>
              <td className="px-4 py-3 text-right text-slate-700">
                {formatMoneyOrDash(p.marketValue)}
              </td>
              <td className="px-4 py-3 text-right">
                <PnLCell pnl={p.unrealizedPnL} pnlPct={p.unrealizedPnLPct} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
