import { forwardRef, type SelectHTMLAttributes } from 'react';
import { ALLOWED_TICKERS, MARKETS } from '@/constants/tickers';

/**
 * Dropdown agrupado por mercado (NYSE, NASDAQ, LSE, TSE, ASX) alimentado de
 * {@link ALLOWED_TICKERS} (espejo de backend AllowedTickers.java).
 *
 * <p>Se usa como un `<select>` cualquiera — soporta spread de `register('ticker')`
 * de react-hook-form porque expone `ref` vía {@link forwardRef}.
 */
export const TickerDropdown = forwardRef<
  HTMLSelectElement,
  SelectHTMLAttributes<HTMLSelectElement>
>(function TickerDropdown(props, ref) {
  return (
    <select ref={ref} {...props}>
      <option value="">— Selecciona un ticker —</option>
      {MARKETS.map((market) => (
        <optgroup key={market} label={market}>
          {ALLOWED_TICKERS[market].map((ticker) => (
            <option key={ticker} value={ticker}>
              {ticker}
            </option>
          ))}
        </optgroup>
      ))}
    </select>
  );
});
