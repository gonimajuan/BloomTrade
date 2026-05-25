import { Line, LineChart } from 'recharts';

interface Props {
  /** Lista cronológica de closes en string scale=2 (del backend). */
  data: string[];
  /** Tendencia del día. true=verde, false=rojo, null=neutro (gris). */
  positive: boolean | null;
}

/**
 * Mini-chart intradía 100×30px sin ejes/grid/tooltip (HU-F18 plan D11).
 * Si la serie está vacía (Alpaca bars vacíos en weekend/holiday o adapter failed),
 * renderiza "—" en gris ocupando la misma anchura para no romper el grid.
 */
export function Sparkline({ data, positive }: Props) {
  if (!data || data.length === 0) {
    return (
      <div
        className="flex w-[100px] items-center justify-center text-sm text-slate-400"
        aria-label="Sin datos intradía"
      >
        —
      </div>
    );
  }
  const points = data.map((d) => ({ value: Number.parseFloat(d) }));
  const stroke =
    positive === null
      ? 'rgb(148 163 184)' // slate-400
      : positive
        ? 'rgb(5 150 105)' // emerald-600
        : 'rgb(225 29 72)'; // rose-600
  return (
    <LineChart width={100} height={30} data={points}>
      <Line
        type="monotone"
        dataKey="value"
        stroke={stroke}
        dot={false}
        strokeWidth={1.5}
        isAnimationActive={false}
      />
    </LineChart>
  );
}
