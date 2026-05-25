package co.edu.unbosque.bloomtrade.portfolio.service;

import co.edu.unbosque.bloomtrade.integration.alpaca.MarketDataAdapter;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Coordina el fan-out paralelo a {@link MarketDataAdapter} para enriquecer
 * {@code GET /api/v1/portfolio/positions} con mark-to-market (HU-F16 Lote B).
 *
 * <p>Plan D1: ejecuta un {@link CompletableFuture} por ticker sobre el
 * {@code marketDataExecutor} dedicado (no contamina Tomcat ni ForkJoinPool common).
 * Plan D2: cada future tiene cap individual de {@value #PER_TICKER_TIMEOUT_MS} ms vía
 * {@link CompletableFuture#completeOnTimeout} — si vence o lanza excepción, el valor para
 * ese ticker es {@code null}. El endpoint queda bounded en ~1.5s independientemente del
 * comportamiento interno del adapter (cuyos 3 retries con backoff exp pueden tomar ~7s).
 * Plan D3: el {@link Map} resultante refleja fallos parciales como entradas con valor
 * {@code null}; el caller decide cómo agregar el flag {@code marketDataAvailable}.
 */
@Component
public class MarketDataOrchestrator {

    /** Cap por ticker en ms (plan D2). Bounded el endpoint completo en ~1.5s. */
    public static final long PER_TICKER_TIMEOUT_MS = 1_500L;

    private static final Logger log = LoggerFactory.getLogger(MarketDataOrchestrator.class);

    private final MarketDataAdapter marketDataAdapter;
    private final ExecutorService marketDataExecutor;

    public MarketDataOrchestrator(
            MarketDataAdapter marketDataAdapter,
            @Qualifier("marketDataExecutor") ExecutorService marketDataExecutor) {
        this.marketDataAdapter = marketDataAdapter;
        this.marketDataExecutor = marketDataExecutor;
    }

    /**
     * Resuelve el precio actual para cada ticker en paralelo. Cada ticker mapea a su precio
     * o a {@code null} si timeout/excepción. Empty input → empty map (sin invocar al adapter).
     *
     * <p>Logging: INFO al cerrar con counts de éxitos vs nulls; WARN por cada ticker fallido
     * con la causa.
     */
    public Map<String, BigDecimal> fetchPrices(Collection<String> tickers) {
        if (tickers == null || tickers.isEmpty()) {
            return Map.of();
        }
        long startNanos = System.nanoTime();

        Map<String, CompletableFuture<BigDecimal>> futures = new LinkedHashMap<>();
        for (String ticker : tickers) {
            CompletableFuture<BigDecimal> future =
                    CompletableFuture.supplyAsync(
                                    () -> marketDataAdapter.getLatestPrice(ticker),
                                    marketDataExecutor)
                            .completeOnTimeout(null, PER_TICKER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .exceptionally(
                                    throwable -> {
                                        log.warn(
                                                "Market data ticker={} fallo: {}",
                                                ticker,
                                                throwable.getMessage());
                                        return null;
                                    });
            futures.put(ticker, future);
        }

        CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0])).join();

        Map<String, BigDecimal> result = new HashMap<>();
        int successCount = 0;
        int nullCount = 0;
        for (Map.Entry<String, CompletableFuture<BigDecimal>> entry : futures.entrySet()) {
            BigDecimal price = entry.getValue().getNow(null);
            result.put(entry.getKey(), price);
            if (price == null) {
                nullCount++;
            } else {
                successCount++;
            }
        }

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        log.info(
                "MarketData fan-out: tickers={} success={} null={} elapsedMs={}",
                tickers.size(),
                successCount,
                nullCount,
                elapsedMs);

        return result;
    }
}
