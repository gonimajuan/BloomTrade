import { TickerRow } from '@/features/dashboard/components/TickerRow';
import { Card } from '@/components/ui/Card';
import type { MarketGroupDto } from '@/types/api';

interface Props {
  tickers: MarketGroupDto[];
  selectedTicker: string | null;
  onSelectTicker: (ticker: string) => void;
}

/**
 * Grid de los 25 activos agrupados por mercado (HU-F18 plan D12).
 * Revamp Lote D: Cards glass por mercado + header como pill violet sutil.
 * Layout 1/2/5 columnas (desktop only — el revamp es ≥1024px).
 */
export function TickerGrid({ tickers, selectedTicker, onSelectTicker }: Props) {
  return (
    <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-5">
      {tickers.map((group) => (
        <Card key={group.market} variant="glass" className="p-4">
          <h3 className="mb-3 inline-flex items-center rounded-full bg-violet-500/15 px-2.5 py-0.5 text-[10px] font-bold uppercase tracking-[0.15em] text-violet-200">
            {group.market}
          </h3>
          <div className="divide-y divide-white/5">
            {group.items.map((it) => (
              <TickerRow
                key={it.ticker}
                item={it}
                selected={it.ticker === selectedTicker}
                onSelect={onSelectTicker}
              />
            ))}
          </div>
        </Card>
      ))}
    </div>
  );
}
