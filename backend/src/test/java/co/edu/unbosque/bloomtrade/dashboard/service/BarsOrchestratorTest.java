package co.edu.unbosque.bloomtrade.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import co.edu.unbosque.bloomtrade.dashboard.dto.IntradayBar;
import co.edu.unbosque.bloomtrade.integration.alpaca.MarketDataAdapter;
import co.edu.unbosque.bloomtrade.integration.alpaca.MarketDataUnavailableException;
import java.math.BigDecimal;
import java.time.Instant;
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
 * Tests unit del {@link BarsOrchestrator} (HU-F18 Lote B). Mismo patrón que
 * {@code MarketDataOrchestratorTest} de F16 pero retornando {@code List<IntradayBar>}.
 */
@ExtendWith(MockitoExtension.class)
class BarsOrchestratorTest {

    @Mock private MarketDataAdapter marketDataAdapter;

    private ExecutorService executor;
    private BarsOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);
        orchestrator = new BarsOrchestrator(marketDataAdapter, executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private static IntradayBar bar(String close) {
        return new IntradayBar(
                Instant.parse("2026-05-25T13:30:00Z"),
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                new BigDecimal(close),
                1000L);
    }

    @Test
    void fetchBars_emptyInput_returnsEmptyMap() {
        Map<String, List<IntradayBar>> result = orchestrator.fetchBars(List.of());
        assertThat(result).isEmpty();
    }

    @Test
    void fetchBars_nullInput_returnsEmptyMap() {
        Map<String, List<IntradayBar>> result = orchestrator.fetchBars(null);
        assertThat(result).isEmpty();
    }

    @Test
    void fetchBars_allSuccess_returnsAllSeries() {
        when(marketDataAdapter.getIntradayBars("AAPL"))
                .thenReturn(List.of(bar("193.20"), bar("193.50")));
        when(marketDataAdapter.getIntradayBars("MSFT"))
                .thenReturn(List.of(bar("408.50")));

        Map<String, List<IntradayBar>> result = orchestrator.fetchBars(List.of("AAPL", "MSFT"));

        assertThat(result).hasSize(2);
        assertThat(result.get("AAPL")).hasSize(2);
        assertThat(result.get("MSFT")).hasSize(1);
    }

    @Test
    void fetchBars_exception_returnsEmptyListForThatTicker() {
        when(marketDataAdapter.getIntradayBars("AAPL")).thenReturn(List.of(bar("193.20")));
        when(marketDataAdapter.getIntradayBars("BADX"))
                .thenThrow(new MarketDataUnavailableException("404"));

        Map<String, List<IntradayBar>> result = orchestrator.fetchBars(List.of("AAPL", "BADX"));

        assertThat(result).hasSize(2);
        assertThat(result.get("AAPL")).hasSize(1);
        assertThat(result.get("BADX")).isEmpty();
    }

    @Test
    void fetchBars_timeout_returnsEmptyListAndStaysBoundedUnderCap() {
        when(marketDataAdapter.getIntradayBars("AAPL")).thenReturn(List.of(bar("193.20")));
        when(marketDataAdapter.getIntradayBars("SLOW"))
                .thenAnswer(
                        invocation -> {
                            Thread.sleep(3_000); // excede cap 1.5s
                            return List.of(bar("999.00"));
                        });

        long startNanos = System.nanoTime();
        Map<String, List<IntradayBar>> result = orchestrator.fetchBars(List.of("AAPL", "SLOW"));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertThat(result.get("AAPL")).hasSize(1);
        assertThat(result.get("SLOW")).isEmpty();
        assertThat(elapsedMs).isLessThan(2_500L);
    }

    @Test
    void fetchBars_allFail_returnsAllEmpty() {
        when(marketDataAdapter.getIntradayBars("AAPL"))
                .thenThrow(new MarketDataUnavailableException("503"));
        when(marketDataAdapter.getIntradayBars("MSFT"))
                .thenThrow(new MarketDataUnavailableException("503"));

        Map<String, List<IntradayBar>> result = orchestrator.fetchBars(List.of("AAPL", "MSFT"));

        assertThat(result).hasSize(2);
        assertThat(result.get("AAPL")).isEmpty();
        assertThat(result.get("MSFT")).isEmpty();
    }
}
