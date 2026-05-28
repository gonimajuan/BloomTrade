import { AlertTriangle } from 'lucide-react';
import { portfolioMessages } from '@/lib/messages.es';
import type { MarketDataAvailability } from '@/types/api';

interface Props {
  status: MarketDataAvailability;
}

/**
 * Banner condicional según `marketDataAvailable` (SPEC §12.1 + plan D3).
 * Revamp Lote D: glass dark tints amber para ambos casos (partial menos intenso).
 */
export function MarketDataBanner({ status }: Props) {
  if (status === 'true') return null;
  const isPartial = status === 'partial';
  const palette = isPartial
    ? 'border-amber-500/25 bg-amber-500/10 text-amber-200'
    : 'border-amber-500/40 bg-amber-500/15 text-amber-100';
  const text = isPartial
    ? portfolioMessages.marketDataPartial
    : portfolioMessages.marketDataDown;
  return (
    <div
      role="status"
      className={`flex items-start gap-2 rounded-xl border px-4 py-3 text-sm backdrop-blur-sm ${palette}`}
    >
      <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
      <p>{text}</p>
    </div>
  );
}
