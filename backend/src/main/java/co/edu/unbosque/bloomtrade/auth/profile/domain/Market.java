package co.edu.unbosque.bloomtrade.auth.profile.domain;

/**
 * Mercados internacionales soportados (ARCHITECTURE.md §1).
 *
 * <p>El catálogo de 25 activos {@code AllowedTickers} agrupa 5 tickers por mercado. El frontend
 * usa este enum para presentar el grid de selección agrupado por mercado en HU-F04.
 */
public enum Market {
    NYSE,
    NASDAQ,
    LSE,
    TSE,
    ASX
}
