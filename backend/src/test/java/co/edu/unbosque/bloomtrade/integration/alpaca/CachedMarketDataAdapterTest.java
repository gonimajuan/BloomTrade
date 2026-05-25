package co.edu.unbosque.bloomtrade.integration.alpaca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Tests unit del {@link CachedMarketDataAdapter} con Mockito sobre {@link RedisTemplate}.
 *
 * <p>Cubre HU-F18 plan D1 (decorator wrapper), D2 (RedisTemplate manual), D3 (key format),
 * D16 (no cachear nulls), y D-CACHE-LOCATION fallback Redis-down.
 */
@ExtendWith(MockitoExtension.class)
class CachedMarketDataAdapterTest {

    @Mock private MarketDataAdapter marketDataAdapter;
    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private ExecutorService executor;
    private CachedMarketDataAdapter cached;

    @BeforeEach
    void setup() {
        // Pool real para el batch fan-out (la concurrencia es parte del test).
        executor = Executors.newFixedThreadPool(4);
        cached = new CachedMarketDataAdapter(marketDataAdapter, redisTemplate, executor);
        // lenient: getLatestPrices_emptyInput retorna antes de tocar opsForValue.
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    // ─── Singular getLatestPrice ────────────────────────────────────────────────────────

    @Test
    void getLatestPrice_cacheHit_returnsCachedWithoutCallingAdapter() {
        when(valueOps.get("market-data:price:AAPL")).thenReturn("193.20");

        BigDecimal price = cached.getLatestPrice("AAPL");

        assertThat(price).isEqualByComparingTo(new BigDecimal("193.20"));
        verify(marketDataAdapter, never()).getLatestPrice(any());
        verify(valueOps, never()).set(any(), any(), any(Duration.class));
    }

    @Test
    void getLatestPrice_cacheMiss_callsAdapterAndSetsCache() {
        when(valueOps.get("market-data:price:MSFT")).thenReturn(null);
        when(marketDataAdapter.getLatestPrice("MSFT")).thenReturn(new BigDecimal("408.5000"));

        BigDecimal price = cached.getLatestPrice("MSFT");

        assertThat(price).isEqualByComparingTo(new BigDecimal("408.50"));
        verify(marketDataAdapter, times(1)).getLatestPrice("MSFT");
        verify(valueOps, times(1)).set("market-data:price:MSFT", "408.5000", Duration.ofSeconds(30));
    }

    @Test
    void getLatestPrice_adapterThrowsMarketDataUnavailable_propagatesAndDoesNotCacheNull() {
        when(valueOps.get("market-data:price:TSLA")).thenReturn(null);
        when(marketDataAdapter.getLatestPrice("TSLA"))
                .thenThrow(new MarketDataUnavailableException("Alpaca down"));

        assertThatThrownBy(() -> cached.getLatestPrice("TSLA"))
                .isInstanceOf(MarketDataUnavailableException.class);
        verify(valueOps, never()).set(any(), any(), any(Duration.class));
    }

    @Test
    void getLatestPrice_redisDownOnGet_fallsBackToAdapterDirectly() {
        when(valueOps.get("market-data:price:AAPL"))
                .thenThrow(new RedisConnectionFailureException("Redis down"));
        when(marketDataAdapter.getLatestPrice("AAPL")).thenReturn(new BigDecimal("190.00"));

        BigDecimal price = cached.getLatestPrice("AAPL");

        assertThat(price).isEqualByComparingTo(new BigDecimal("190.00"));
        verify(marketDataAdapter, times(1)).getLatestPrice("AAPL");
        // El set también va a fallar pero swallowed silenciosamente — no propaga.
    }

    @Test
    void getLatestPrice_keyFormatIsMarketDataPricePrefix() {
        when(valueOps.get("market-data:price:GOOGL")).thenReturn("140.55");

        cached.getLatestPrice("GOOGL");

        verify(valueOps, times(1)).get("market-data:price:GOOGL");
    }

    // ─── Batch getLatestPrices ──────────────────────────────────────────────────────────

    @Test
    void getLatestPrices_emptyInput_returnsEmptyMapWithoutTouchingRedisOrAdapter() {
        Map<String, BigDecimal> result = cached.getLatestPrices(List.of());

        assertThat(result).isEmpty();
        verify(redisTemplate, never()).opsForValue();
        verify(marketDataAdapter, never()).getLatestPrice(any());
    }

    @Test
    void getLatestPrices_partialCacheHit_fetchesMissesAndCachesNew() {
        // multiGet retorna AAPL cacheado pero MSFT y TSLA en miss (null).
        when(valueOps.multiGet(
                        Arrays.asList(
                                "market-data:price:AAPL",
                                "market-data:price:MSFT",
                                "market-data:price:TSLA")))
                .thenReturn(Arrays.asList("193.20", null, null));
        when(marketDataAdapter.getLatestPrice("MSFT")).thenReturn(new BigDecimal("412.00"));
        when(marketDataAdapter.getLatestPrice("TSLA"))
                .thenThrow(new MarketDataUnavailableException("alpaca down"));

        Map<String, BigDecimal> result = cached.getLatestPrices(List.of("AAPL", "MSFT", "TSLA"));

        assertThat(result).hasSize(3);
        assertThat(result.get("AAPL")).isEqualByComparingTo(new BigDecimal("193.20"));
        assertThat(result.get("MSFT")).isEqualByComparingTo(new BigDecimal("412.00"));
        assertThat(result.get("TSLA")).isNull();
        // Solo MSFT se cachea (AAPL ya estaba, TSLA falló).
        verify(valueOps, times(1)).set(eq("market-data:price:MSFT"), eq("412.00"), eq(Duration.ofSeconds(30)));
        verify(valueOps, never()).set(eq("market-data:price:TSLA"), any(), any(Duration.class));
    }

    @Test
    void getLatestPrices_allCacheHit_doesNotCallAdapter() {
        when(valueOps.multiGet(
                        Arrays.asList("market-data:price:AAPL", "market-data:price:MSFT")))
                .thenReturn(Arrays.asList("193.20", "408.50"));

        Map<String, BigDecimal> result = cached.getLatestPrices(List.of("AAPL", "MSFT"));

        assertThat(result.get("AAPL")).isEqualByComparingTo(new BigDecimal("193.20"));
        assertThat(result.get("MSFT")).isEqualByComparingTo(new BigDecimal("408.50"));
        verify(marketDataAdapter, never()).getLatestPrice(any());
        verify(valueOps, never()).set(any(), any(), any(Duration.class));
    }

    @Test
    void getLatestPrices_redisDownOnMultiGet_treatsAllAsMissAndFanOuts() {
        when(valueOps.multiGet(any())).thenThrow(new RedisConnectionFailureException("Redis down"));
        when(marketDataAdapter.getLatestPrice("AAPL")).thenReturn(new BigDecimal("193.20"));
        when(marketDataAdapter.getLatestPrice("MSFT")).thenReturn(new BigDecimal("408.50"));

        Map<String, BigDecimal> result = cached.getLatestPrices(List.of("AAPL", "MSFT"));

        assertThat(result.get("AAPL")).isEqualByComparingTo(new BigDecimal("193.20"));
        assertThat(result.get("MSFT")).isEqualByComparingTo(new BigDecimal("408.50"));
        verify(marketDataAdapter, times(1)).getLatestPrice("AAPL");
        verify(marketDataAdapter, times(1)).getLatestPrice("MSFT");
    }
}
