import {
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import type { TickerDashboardDto } from '@/types/api';

interface Props {
  ticker: TickerDashboardDto | null;
}

const currencyFmt = new Intl.NumberFormat('es-CO', {
  style: 'currency',
  currency: 'USD',
  minimumFractionDigits: 2,
});

const percentFmt = new Intl.NumberFormat('es-CO', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

/**
 * Panel grande con la gráfica intradía del ticker seleccionado en el grid.
 * Reemplaza el sparkline mini por fila (HU-F18 Día 10 polish — user request: revamp UI
 * vendrá después con Claude Design).
 *
 * <p>Estados:
 * <ul>
 *   <li>{@code ticker === null}: placeholder "Selecciona un ticker".</li>
 *   <li>{@code sparkline.length === 0}: header + mensaje "Sin datos".</li>
 *   <li>resto: chart recharts con tooltip + eje Y + cartesian grid.</li>
 * </ul>
 */
export function SparklinePanel({ ticker }: Props) {
  if (ticker === null) {
    return (
      <section className="rounded-lg border border-dashed border-slate-300 bg-white p-10 text-center shadow-sm">
        <p className="text-sm text-slate-500">
          Selecciona un ticker del grid para ver su gráfica.
        </p>
      </section>
    );
  }

  const pct =
    ticker.dayChangePct !== null ? Number.parseFloat(ticker.dayChangePct) : null;
  const positive = pct !== null && pct > 0;
  const negative = pct !== null && pct < 0;
  const stroke = positive
    ? 'rgb(5 150 105)' // emerald-600
    : negative
      ? 'rgb(225 29 72)' // rose-600
      : 'rgb(100 116 139)'; // slate-500
  const priceColor = positive
    ? 'text-emerald-600'
    : negative
      ? 'text-rose-600'
      : 'text-slate-500';

  const points = ticker.sparkline.map((d, idx) => ({
    idx,
    value: Number.parseFloat(d),
  }));

  const priceDisplay =
    ticker.currentPrice !== null
      ? currencyFmt.format(Number.parseFloat(ticker.currentPrice))
      : '—';
  const pctDisplay =
    pct !== null ? `${pct > 0 ? '+' : ''}${percentFmt.format(pct)}%` : '—';

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
      <header className="mb-4 flex items-baseline justify-between">
        <div>
          <h2 className="font-mono text-xl font-bold text-slate-900">
            {ticker.ticker}
          </h2>
          <p className="text-xs text-slate-500">Variación reciente — barras de 15 min</p>
        </div>
        <div className="text-right">
          <p className="text-2xl font-semibold text-slate-900">{priceDisplay}</p>
          <p className={`text-sm font-medium ${priceColor}`}>{pctDisplay}</p>
        </div>
      </header>

      {points.length === 0 ? (
        <div className="flex h-[260px] items-center justify-center text-sm text-slate-400">
          Sin datos intradía para este ticker.
        </div>
      ) : (
        <div className="h-[260px] w-full">
          <ResponsiveContainer width="100%" height="100%">
            <LineChart
              data={points}
              margin={{ top: 8, right: 16, left: 0, bottom: 8 }}
            >
              <CartesianGrid strokeDasharray="3 3" stroke="rgb(226 232 240)" />
              <XAxis dataKey="idx" hide />
              <YAxis
                domain={['auto', 'auto']}
                stroke="rgb(148 163 184)"
                fontSize={11}
                tickFormatter={(v: number) => v.toFixed(2)}
                width={56}
              />
              <Tooltip
                formatter={(value: number) => [
                  currencyFmt.format(value),
                  'Close',
                ]}
                labelFormatter={(idx: number) => `Barra ${idx + 1}`}
                contentStyle={{
                  borderRadius: 6,
                  border: '1px solid rgb(226 232 240)',
                  fontSize: 12,
                }}
              />
              <Line
                type="monotone"
                dataKey="value"
                stroke={stroke}
                strokeWidth={2}
                dot={false}
                isAnimationActive={false}
              />
            </LineChart>
          </ResponsiveContainer>
        </div>
      )}
    </section>
  );
}
