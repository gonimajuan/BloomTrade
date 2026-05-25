package co.edu.unbosque.bloomtrade.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import co.edu.unbosque.bloomtrade.integration.alpaca.MarketDataAdapter;
import co.edu.unbosque.bloomtrade.integration.alpaca.MarketDataUnavailableException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests unit del {@link MarketDataOrchestrator} (HU-F16 Lote B — T2.3–T2.8). Usa executor real
 * con 2 threads (la concurrencia es parte del comportamiento bajo test) y mockea
 * {@link MarketDataAdapter}.
 */
@ExtendWith(MockitoExtension.class)
class MarketDataOrchestratorTest {

    @Mock private MarketDataAdapter marketDataAdapter;

    private ExecutorService executor;
    private MarketDataOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);
        orchestrator = new MarketDataOrchestrator(marketDataAdapter, executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void fetchPrices_emptyInput_returnsEmptyMap() {
        Map<String, BigDecimal> result = orchestrator.fetchPrices(List.of());

        assertThat(result).isEmpty();
    }

    @Test
    void fetchPrices_nullInput_returnsEmptyMap() {
        Map<String, BigDecimal> result = orchestrator.fetchPrices(null);

        assertThat(result).isEmpty();
    }

    @Test
    void fetchPrices_allSuccess_returnsAllPrices() {
        when(marketDataAdapter.getLatestPrice("AAPL")).thenReturn(new BigDecimal("193.20"));
        when(marketDataAdapter.getLatestPrice("MSFT")).thenReturn(new BigDecimal("408.50"));
        when(marketDataAdapter.getLatestPrice("TSLA")).thenReturn(new BigDecimal("235.10"));

        Map<String, BigDecimal> result = orchestrator.fetchPrices(List.of("AAPL", "MSFT", "TSLA"));

        assertThat(result)
                .hasSize(3)
                .containsEntry("AAPL", new BigDecimal("193.20"))
                .containsEntry("MSFT", new BigDecimal("408.50"))
                .containsEntry("TSLA", new BigDecimal("235.10"));
    }

    @Test
    void fetchPrices_oneException_returnsNullForThatTicker() {
        when(marketDataAdapter.getLatestPrice("AAPL")).thenReturn(new BigDecimal("193.20"));
        when(marketDataAdapter.getLatestPrice("BADX"))
                .thenThrow(new MarketDataUnavailableException("Alpaca Data 404 para ticker BADX"));
        when(marketDataAdapter.getLatestPrice("MSFT")).thenReturn(new BigDecimal("408.50"));

        Map<String, BigDecimal> result = orchestrator.fetchPrices(List.of("AAPL", "BADX", "MSFT"));

        assertThat(result)
                .hasSize(3)
                .containsEntry("AAPL", new BigDecimal("193.20"))
                .containsEntry("MSFT", new BigDecimal("408.50"));
        assertThat(result.get("BADX")).isNull();
    }

    @Test
    void fetchPrices_oneTimeout_returnsNullForThatTicker() {
        when(marketDataAdapter.getLatestPrice("AAPL")).thenReturn(new BigDecimal("193.20"));
        when(marketDataAdapter.getLatestPrice("SLOW"))
                .thenAnswer(
                        invocation -> {
                            // Excede el cap de 1.5s del orchestrator (plan D2).
                            Thread.sleep(3_000);
                            return new BigDecimal("100.00");
                        });

        long startNanos = System.nanoTime();
        Map<String, BigDecimal> result = orchestrator.fetchPrices(List.of("AAPL", "SLOW"));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertThat(result).hasSize(2).containsEntry("AAPL", new BigDecimal("193.20"));
        assertThat(result.get("SLOW")).isNull();
        // El timeout per-ticker corta el wait — no debería pasar de ~2s (1.5s cap + overhead).
        assertThat(elapsedMs)
                .as("Endpoint debe estar bounded por per-ticker timeout, no por el sleep del mock")
                .isLessThan(2_500L);
    }

    @Test
    void fetchPrices_allFail_returnsAllNulls() {
        when(marketDataAdapter.getLatestPrice("AAPL"))
                .thenThrow(new MarketDataUnavailableException("Alpaca Data 503"));
        when(marketDataAdapter.getLatestPrice("MSFT"))
                .thenThrow(new MarketDataUnavailableException("Alpaca Data 503"));
        when(marketDataAdapter.getLatestPrice("TSLA"))
                .thenThrow(new MarketDataUnavailableException("Alpaca Data 503"));

        Map<String, BigDecimal> result = orchestrator.fetchPrices(List.of("AAPL", "MSFT", "TSLA"));

        assertThat(result).hasSize(3);
        assertThat(result.get("AAPL")).isNull();
        assertThat(result.get("MSFT")).isNull();
        assertThat(result.get("TSLA")).isNull();
    }

    @Test
    void fetchPrices_partialMix_returnsBothSuccessAndNulls() {
        when(marketDataAdapter.getLatestPrice("AAPL")).thenReturn(new BigDecimal("193.20"));
        when(marketDataAdapter.getLatestPrice("MSFT")).thenReturn(new BigDecimal("408.50"));
        when(marketDataAdapter.getLatestPrice("BADX"))
                .thenThrow(new MarketDataUnavailableException("404"));
        when(marketDataAdapter.getLatestPrice("SLOW"))
                .thenAnswer(
                        invocation -> {
                            Thread.sleep(3_000);
                            return new BigDecimal("999.00");
                        });

        Map<String, BigDecimal> result =
                orchestrator.fetchPrices(List.of("AAPL", "MSFT", "BADX", "SLOW"));

        assertThat(result).hasSize(4);
        assertThat(result.get("AAPL")).isEqualByComparingTo("193.20");
        assertThat(result.get("MSFT")).isEqualByComparingTo("408.50");
        assertThat(result.get("BADX")).isNull();
        assertThat(result.get("SLOW")).isNull();
    }
}
