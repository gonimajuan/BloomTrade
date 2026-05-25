import { forwardRef, type SelectHTMLAttributes } from 'react';
import { ALLOWED_TICKERS, MARKETS } from '@/constants/tickers';

interface TickerDropdownProps extends SelectHTMLAttributes<HTMLSelectElement> {
  /**
   * HU-F18 Lote E (deuda #15): si está presente, el dropdown solo muestra tickers cuyo
   * símbolo está incluido en el set. Mercados sin tickers owned no se renderizan.
   * Útil para side=SELL: solo mostrar tickers con posición. {@code undefined} → muestra
   * los 25 (comportamiento BUY default).
   */
  ownedTickers?: ReadonlySet<string>;
}

/**
 * Dropdown agrupado por mercado (NYSE, NASDAQ, LSE, TSE, ASX) alimentado de
 * {@link ALLOWED_TICKERS} (espejo de backend AllowedTickers.java).
 *
 * <p>Se usa como un `<select>` cualquiera — soporta spread de `register('ticker')`
 * de react-hook-form porque expone `ref` vía {@link forwardRef}.
 */
export const TickerDropdown = forwardRef<HTMLSelectElement, TickerDropdownProps>(
  function TickerDropdown({ ownedTickers, ...rest }, ref) {
    const filter = ownedTickers;
    const groups = MARKETS.map((market) => {
      const tickers = ALLOWED_TICKERS[market].filter(
        (t) => filter === undefined || filter.has(t),
      );
      return { market, tickers };
    }).filter((g) => g.tickers.length > 0);

    return (
      <select ref={ref} {...rest}>
        <option value="">— Selecciona un ticker —</option>
        {groups.map((group) => (
          <optgroup key={group.market} label={group.market}>
            {group.tickers.map((ticker) => (
              <option key={ticker} value={ticker}>
                {ticker}
              </option>
            ))}
          </optgroup>
        ))}
      </select>
    );
  },
);
