import { AlertTriangle } from 'lucide-react';
import { portfolioMessages } from '@/lib/messages.es';
import type { MarketDataAvailability } from '@/types/api';

interface Props {
  status: MarketDataAvailability;
}

/**
 * Banner condicional según `marketDataAvailable` (SPEC §12.1 + plan D3):
 *  - "true": no renderiza nada.
 *  - "partial": banner naranja (algunos precios faltan, marcados con "—").
 *  - "false": banner amarillo (sin precios actuales, solo costo promedio).
 */
export function MarketDataBanner({ status }: Props) {
  if (status === 'true') return null;
  const isPartial = status === 'partial';
  const palette = isPartial
    ? 'border-orange-300 bg-orange-50 text-orange-900'
    : 'border-amber-300 bg-amber-50 text-amber-900';
  const text = isPartial
    ? portfolioMessages.marketDataPartial
    : portfolioMessages.marketDataDown;
  return (
    <div
      role="status"
      className={`flex items-start gap-2 rounded-md border px-4 py-3 text-sm ${palette}`}
    >
      <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
      <p>{text}</p>
    </div>
  );
}
