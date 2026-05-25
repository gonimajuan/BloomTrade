package co.edu.unbosque.bloomtrade.integration.alpaca;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
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
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Decorator sobre {@link MarketDataAdapter} con cache Redis TTL 30s (HU-F18 Lote A —
 * plan D1 + D2 + D3 + D16). Cierra deuda viva #19 del handoff F16+F21.
 *
 * <p>Diseño:
 * <ul>
 *   <li>Singular {@link #getLatestPrice}: cache check → si hit, return; si miss, delegate al
 *       adapter + set Redis + return.</li>
 *   <li>Batch {@link #getLatestPrices}: multi-get Redis → fan-out paralelo solo de los misses
 *       sobre {@code marketDataExecutor} (reusa pool de F16) con cap individual 1.5s por
 *       ticker → set en Redis solo los exitosos → merge.</li>
 * </ul>
 *
 * <p>Política de nulls (plan D16): NUNCA cachea valores null. Si Alpaca falla, propaga
 * {@link MarketDataUnavailableException} en el singular o devuelve {@code null} para ese
 * ticker en el batch (sin contaminar Redis con el fallo). Razón: si cacheamos null, un
 * fallo transitorio de Alpaca contamina el cache 30s aún cuando Alpaca se recupere.
 *
 * <p>Política de Redis down (plan D2): si {@code RedisTemplate} lanza
 * {@link DataAccessException} (incluye {@code RedisConnectionFailureException}), log WARN
 * y fallback transparente al adapter directo. El endpoint sigue funcionando, paga el costo
 * en Alpaca calls.
 */
@Component
public class CachedMarketDataAdapter {

    /** Prefijo de keys Redis para precios spot (plan D3). */
    public static final String KEY_PREFIX = "market-data:price:";

    /** TTL del cache (plan D2: 30s para amortiguar polling /dashboard a 30s también). */
    public static final Duration TTL = Duration.ofSeconds(30);

    /** Cap por ticker en ms para el fan-out batch (consistente con MarketDataOrchestrator). */
    public static final long PER_TICKER_TIMEOUT_MS = 1_500L;

    private static final Logger log = LoggerFactory.getLogger(CachedMarketDataAdapter.class);

    private final MarketDataAdapter marketDataAdapter;
    private final RedisTemplate<String, String> redisTemplate;
    private final ExecutorService marketDataExecutor;

    public CachedMarketDataAdapter(
            MarketDataAdapter marketDataAdapter,
            RedisTemplate<String, String> redisTemplate,
            @Qualifier("marketDataExecutor") ExecutorService marketDataExecutor) {
        this.marketDataAdapter = marketDataAdapter;
        this.redisTemplate = redisTemplate;
        this.marketDataExecutor = marketDataExecutor;
    }

    /**
     * Versión singular con cache: hit retorna del cache, miss delegate + set.
     *
     * @throws MarketDataUnavailableException si Alpaca falla (igual contrato que
     *     {@link MarketDataAdapter#getLatestPrice}). Nunca cachea esta excepción.
     */
    public BigDecimal getLatestPrice(String ticker) {
        String key = KEY_PREFIX + ticker;
        String cached = safeGet(key);
        if (cached != null) {
            log.debug("Cache HIT ticker={} value={}", ticker, cached);
            return new BigDecimal(cached);
        }
        log.debug("Cache MISS ticker={}", ticker);
        BigDecimal price = marketDataAdapter.getLatestPrice(ticker);
        safeSet(key, price.toPlainString());
        return price;
    }

    /**
     * Versión batch con cache multi-get + fan-out paralelo de los misses.
     *
     * <p>Devuelve {@link Map} con un entry por ticker de entrada. Valor {@code null} significa
     * que ese ticker falló (Alpaca down/429/4xx o timeout 1.5s) — el caller decide cómo
     * señalarlo al cliente. Empty input devuelve {@link Map#of()} sin tocar Redis ni Alpaca.
     */
    public Map<String, BigDecimal> getLatestPrices(Collection<String> tickers) {
        if (tickers == null || tickers.isEmpty()) {
            return Map.of();
        }
        long startNanos = System.nanoTime();
        List<String> tickerList = new ArrayList<>(tickers);
        List<String> keyList = tickerList.stream().map(t -> KEY_PREFIX + t).toList();

        // multi-get del cache (mismo orden que keyList)
        List<String> cachedValues = safeMultiGet(keyList);

        Map<String, BigDecimal> result = new LinkedHashMap<>();
        List<String> missTickers = new ArrayList<>();
        int hits = 0;
        for (int i = 0; i < tickerList.size(); i++) {
            String ticker = tickerList.get(i);
            String value = cachedValues == null ? null : cachedValues.get(i);
            if (value != null) {
                result.put(ticker, new BigDecimal(value));
                hits++;
            } else {
                result.put(ticker, null); // placeholder, sobreescrito si fan-out devuelve precio
                missTickers.add(ticker);
            }
        }

        // Fan-out paralelo solo de los misses
        if (!missTickers.isEmpty()) {
            Map<String, CompletableFuture<BigDecimal>> futures = new HashMap<>();
            for (String ticker : missTickers) {
                CompletableFuture<BigDecimal> future =
                        CompletableFuture.supplyAsync(
                                        () -> marketDataAdapter.getLatestPrice(ticker),
                                        marketDataExecutor)
                                .completeOnTimeout(
                                        null, PER_TICKER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                                .exceptionally(
                                        throwable -> {
                                            log.warn(
                                                    "Cache miss ticker={} fallo Alpaca: {}",
                                                    ticker,
                                                    throwable.getMessage());
                                            return null;
                                        });
                futures.put(ticker, future);
            }
            CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0])).join();
            for (Map.Entry<String, CompletableFuture<BigDecimal>> entry : futures.entrySet()) {
                BigDecimal price = entry.getValue().getNow(null);
                result.put(entry.getKey(), price);
                if (price != null) {
                    // Solo cachear los exitosos (plan D16: no cachear nulls).
                    safeSet(KEY_PREFIX + entry.getKey(), price.toPlainString());
                }
            }
        }

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        long nullCount = result.values().stream().filter(v -> v == null).count();
        log.info(
                "CachedMarketDataAdapter batch: tickers={} cacheHits={} alpacaCalls={} nullResults={} elapsedMs={}",
                tickerList.size(),
                hits,
                missTickers.size(),
                nullCount,
                elapsedMs);
        return result;
    }

    /**
     * Wrapper sobre {@link RedisTemplate#opsForValue() opsForValue().get} con fallback silencioso
     * a {@code null} si Redis está caído. NO propaga la excepción — degrada a cache miss.
     */
    private String safeGet(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (DataAccessException e) {
            log.warn("Redis no disponible (GET key={}): {} — fallback a Alpaca directo", key, e.getMessage());
            return null;
        }
    }

    /**
     * Wrapper sobre {@code multiGet} con fallback silencioso. Si Redis falla, retorna
     * {@code null} → todos los tickers se marcan como miss y se hace fan-out completo.
     */
    private List<String> safeMultiGet(List<String> keys) {
        try {
            return redisTemplate.opsForValue().multiGet(keys);
        } catch (DataAccessException e) {
            log.warn(
                    "Redis no disponible (MGET keys.size={}): {} — fallback a Alpaca para todos",
                    keys.size(),
                    e.getMessage());
            return null;
        }
    }

    /**
     * Wrapper sobre {@code SET key value EX 30} con fallback silencioso. Si Redis falla,
     * el valor no se cachea pero se retorna al cliente igual.
     */
    private void safeSet(String key, String value) {
        try {
            redisTemplate.opsForValue().set(key, value, TTL);
        } catch (DataAccessException e) {
            log.warn("Redis no disponible (SET key={}): {} — valor no cacheado", key, e.getMessage());
        }
    }
}
