import { Line, LineChart } from 'recharts';

interface Props {
  /** Lista cronológica de closes en string scale=2 (del backend). */
  data: string[];
  /** Tendencia del día. true=verde, false=rojo, null=neutro (gris). */
  positive: boolean | null;
}

/**
 * Mini-chart intradía 100×30px sin ejes/grid/tooltip (HU-F18 plan D11).
 * Revamp Lote D: colors emerald-400/rose-400 (más visibles sobre dark glass).
 */
export function Sparkline({ data, positive }: Props) {
  if (!data || data.length === 0) {
    return (
      <div
        className="flex w-[100px] items-center justify-center text-sm text-slate-500"
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
        ? 'rgb(52 211 153)' // emerald-400
        : 'rgb(251 113 133)'; // rose-400
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
