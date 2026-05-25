import { TickerRow } from '@/features/dashboard/components/TickerRow';
import type { MarketGroupDto } from '@/types/api';

interface Props {
  tickers: MarketGroupDto[];
}

/**
 * Grid de los 25 activos agrupados por mercado (HU-F18 plan D12).
 * Responsive Tailwind: 1 columna mobile, 2 tablet, 5 desktop.
 */
export function TickerGrid({ tickers }: Props) {
  return (
    <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-5">
      {tickers.map((group) => (
        <section
          key={group.market}
          className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm"
        >
          <h3 className="mb-2 text-xs font-bold uppercase tracking-wider text-slate-500">
            {group.market}
          </h3>
          <div className="divide-y divide-slate-100">
            {group.items.map((item) => (
              <TickerRow key={item.ticker} item={item} />
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}
