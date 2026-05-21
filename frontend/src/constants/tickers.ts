// Catálogo de 25 activos del MVP — espejo de backend AllowedTickers.java.
// SINCRONIZAR con backend: co.edu.unbosque.bloomtrade.auth.profile.catalog.AllowedTickers
// (ARCHITECTURE.md §1). Post-MVP: generar este archivo desde el enum del OpenAPI.

export type Market = 'NYSE' | 'NASDAQ' | 'LSE' | 'TSE' | 'ASX';

export const MARKETS: readonly Market[] = ['NYSE', 'NASDAQ', 'LSE', 'TSE', 'ASX'];

export const ALLOWED_TICKERS: Record<Market, readonly string[]> = {
  NYSE: ['AAPL', 'MSFT', 'JNJ', 'JPM', 'XOM'],
  NASDAQ: ['GOOGL', 'AMZN', 'META', 'TSLA', 'NVDA'],
  LSE: ['HSBA', 'BP', 'GSK', 'ULVR', 'BARC'],
  TSE: ['7203', '6758', '9984', '8306', '6861'],
  ASX: ['BHP', 'CBA', 'CSL', 'WES', 'WOW'],
};

export const ALL_TICKERS: readonly string[] = MARKETS.flatMap(
  (m) => ALLOWED_TICKERS[m],
);

export const MAX_TICKERS = 25;
