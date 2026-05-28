import {
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { Card } from '@/components/ui/Card';
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
 * Revamp Lote D: Card glass-elevated + recharts tunneado para dark theme
 * (grid white/8, tooltip glass dark, líneas emerald/rose-400).
 */
export function SparklinePanel({ ticker }: Props) {
  if (ticker === null) {
    return (
      <Card
        variant="glass-outline"
        className="border-dashed p-10 text-center"
      >
        <p className="text-sm text-slate-400">
          Selecciona un ticker del grid para ver su gráfica.
        </p>
      </Card>
    );
  }

  const pct =
    ticker.dayChangePct !== null ? Number.parseFloat(ticker.dayChangePct) : null;
  const positive = pct !== null && pct > 0;
  const negative = pct !== null && pct < 0;
  const stroke = positive
    ? 'rgb(52 211 153)' // emerald-400
    : negative
      ? 'rgb(251 113 133)' // rose-400
      : 'rgb(148 163 184)'; // slate-400
  const priceColor = positive
    ? 'text-emerald-400'
    : negative
      ? 'text-rose-400'
      : 'text-slate-400';

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
    <Card variant="glass-elevated" className="p-6">
      <header className="mb-5 flex items-baseline justify-between">
        <div>
          <h2 className="font-mono text-xl font-bold tracking-tight text-white">
            {ticker.ticker}
          </h2>
          <p className="mt-0.5 text-xs text-slate-400">
            Variación reciente — barras de 15 min
          </p>
        </div>
        <div className="text-right">
          <p className="text-2xl font-semibold tabular-nums text-white">
            {priceDisplay}
          </p>
          <p className={`text-sm font-medium tabular-nums ${priceColor}`}>
            {pctDisplay}
          </p>
        </div>
      </header>

      {points.length === 0 ? (
        <div className="flex h-[260px] items-center justify-center text-sm text-slate-500">
          Sin datos intradía para este ticker.
        </div>
      ) : (
        <div className="h-[260px] w-full">
          <ResponsiveContainer width="100%" height="100%">
            <LineChart
              data={points}
              margin={{ top: 8, right: 16, left: 0, bottom: 8 }}
            >
              <CartesianGrid
                strokeDasharray="3 3"
                stroke="rgb(255 255 255 / 0.08)"
              />
              <XAxis dataKey="idx" hide />
              <YAxis
                domain={['auto', 'auto']}
                stroke="rgb(148 163 184)"
                fontSize={11}
                tickFormatter={(v: number) => v.toFixed(2)}
                width={56}
              />
              <Tooltip
                formatter={(value: number) => [currencyFmt.format(value), 'Close']}
                labelFormatter={(idx: number) => `Barra ${idx + 1}`}
                contentStyle={{
                  borderRadius: 12,
                  border: '1px solid rgba(255 255 255 / 0.15)',
                  background: 'rgba(15 23 42 / 0.95)',
                  backdropFilter: 'blur(12px)',
                  fontSize: 12,
                }}
                itemStyle={{ color: 'rgb(241 245 249)' }}
                labelStyle={{ color: 'rgb(148 163 184)' }}
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
    </Card>
  );
}
