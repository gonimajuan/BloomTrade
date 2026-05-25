package co.edu.unbosque.bloomtrade.auth.profile.catalog;

import co.edu.unbosque.bloomtrade.auth.profile.domain.Market;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Catálogo inmutable de los 25 activos del MVP (ARCHITECTURE.md §1), agrupados por mercado.
 *
 * <p>Encapsulado (TAC-M3): única fuente de verdad backend para validar {@code tickers_of_interest}
 * (HU-F04+F20). El frontend mantiene un duplicado en {@code src/constants/tickers.ts}; ambos
 * derivan conceptualmente de este catálogo (deuda doc-only: post-MVP generar el TS desde el
 * OpenAPI enum).
 *
 * <p>El orden de inserción ({@link LinkedHashMap}) refleja el orden visual deseado en el grid de
 * selección del perfil — mercados de oeste a este por zona horaria.
 */
public final class AllowedTickers {

    private static final Map<Market, List<String>> BY_MARKET = buildByMarket();
    private static final Set<String> ALL = buildAll();

    private AllowedTickers() {}

    private static Map<Market, List<String>> buildByMarket() {
        // HU-F18 D25 emergente — Map.copyOf NO garantiza orden de iteración (Java spec
        // "unspecified"); el dashboard requiere orden NYSE→NASDAQ→LSE→TSE→ASX (oeste→este
        // por timezone). Collections.unmodifiableMap envuelve el LinkedHashMap preservando
        // iteración determinista.
        Map<Market, List<String>> map = new LinkedHashMap<>();
        map.put(Market.NYSE,   List.of("AAPL", "MSFT", "JNJ",  "JPM",  "XOM"));
        map.put(Market.NASDAQ, List.of("GOOGL", "AMZN", "META", "TSLA", "NVDA"));
        map.put(Market.LSE,    List.of("HSBA", "BP",   "GSK",  "ULVR", "BARC"));
        map.put(Market.TSE,    List.of("7203", "6758", "9984", "8306", "6861"));
        map.put(Market.ASX,    List.of("BHP",  "CBA",  "CSL",  "WES",  "WOW"));
        return Collections.unmodifiableMap(map);
    }

    private static Set<String> buildAll() {
        return BY_MARKET.values().stream()
                .flatMap(List::stream)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** {@code true} si {@code ticker} está en el catálogo de 25 (case-sensitive). */
    public static boolean contains(String ticker) {
        return ticker != null && ALL.contains(ticker);
    }

    /** Vista inmutable agrupada por mercado. Orden de mercados y de tickers preservado. */
    public static Map<Market, List<String>> byMarket() {
        return BY_MARKET;
    }

    /** Total de tickers en el catálogo (siempre 25 en el MVP). */
    public static int size() {
        return ALL.size();
    }
}
