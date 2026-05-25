package co.edu.unbosque.bloomtrade.dashboard.service;

import co.edu.unbosque.bloomtrade.dashboard.dto.IntradayBar;
import co.edu.unbosque.bloomtrade.integration.alpaca.MarketDataAdapter;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Fan-out paralelo de {@link MarketDataAdapter#getIntradayBars} para enriquecer el dashboard
 * con sparklines (HU-F18 Lote B — plan D5).
 *
 * <p>Análogo a {@code MarketDataOrchestrator} del módulo Portfolio: un {@link CompletableFuture}
 * por ticker sobre el {@code marketDataExecutor} compartido (F16 D1), con cap individual de
 * {@value #PER_TICKER_TIMEOUT_MS} ms vía {@link CompletableFuture#completeOnTimeout}. Diferencia
 * clave: el default de timeout/exception es {@link Collections#emptyList()} (no null) — los
 * consumers (mapper) tratan vacío como "sparkline no disponible" sin null-check ramificado.
 *
 * <p>V1 sin cache (plan D7 D-SPARKLINE-CACHE): cada poll re-fetches las 25 series. Si rate-limit
 * Alpaca golpea, V2 agrega cache con key {@code market-data:bars:{ticker}:{date}} TTL 5min.
 */
@Component
public class BarsOrchestrator {

    /** Cap por ticker en ms (consistente con {@code MarketDataOrchestrator}). */
    public static final long PER_TICKER_TIMEOUT_MS = 1_500L;

    private static final Logger log = LoggerFactory.getLogger(BarsOrchestrator.class);

    private final MarketDataAdapter marketDataAdapter;
    private final ExecutorService marketDataExecutor;

    public BarsOrchestrator(
            MarketDataAdapter marketDataAdapter,
            @Qualifier("marketDataExecutor") ExecutorService marketDataExecutor) {
        this.marketDataAdapter = marketDataAdapter;
        this.marketDataExecutor = marketDataExecutor;
    }

    /**
     * Resuelve las barras intradía para cada ticker en paralelo. Empty input → empty map.
     * Cada ticker mapea a su lista de bars (puede ser vacía si Alpaca no encontró barras o si
     * el fetch falló por timeout/excepción).
     */
    public Map<String, List<IntradayBar>> fetchBars(Collection<String> tickers) {
        if (tickers == null || tickers.isEmpty()) {
            return Map.of();
        }
        long startNanos = System.nanoTime();

        Map<String, CompletableFuture<List<IntradayBar>>> futures = new LinkedHashMap<>();
        for (String ticker : tickers) {
            CompletableFuture<List<IntradayBar>> future =
                    CompletableFuture.supplyAsync(
                                    () -> marketDataAdapter.getIntradayBars(ticker),
                                    marketDataExecutor)
                            .completeOnTimeout(
                                    Collections.emptyList(),
                                    PER_TICKER_TIMEOUT_MS,
                                    TimeUnit.MILLISECONDS)
                            .exceptionally(
                                    throwable -> {
                                        log.warn(
                                                "Bars fan-out ticker={} fallo: {}",
                                                ticker,
                                                throwable.getMessage());
                                        return Collections.emptyList();
                                    });
            futures.put(ticker, future);
        }

        CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0])).join();

        Map<String, List<IntradayBar>> result = new HashMap<>();
        int successCount = 0;
        int emptyCount = 0;
        for (Map.Entry<String, CompletableFuture<List<IntradayBar>>> entry : futures.entrySet()) {
            List<IntradayBar> bars = entry.getValue().getNow(Collections.emptyList());
            result.put(entry.getKey(), bars);
            if (bars.isEmpty()) {
                emptyCount++;
            } else {
                successCount++;
            }
        }

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        log.info(
                "Bars fan-out: tickers={} withBars={} empty={} elapsedMs={}",
                tickers.size(),
                successCount,
                emptyCount,
                elapsedMs);

        return result;
    }
}
