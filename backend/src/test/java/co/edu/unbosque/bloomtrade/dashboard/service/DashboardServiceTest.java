package co.edu.unbosque.bloomtrade.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import co.edu.unbosque.bloomtrade.auth.profile.catalog.AllowedTickers;
import co.edu.unbosque.bloomtrade.dashboard.dto.AccountEquityDto;
import co.edu.unbosque.bloomtrade.dashboard.dto.DashboardSnapshotResponse;
import co.edu.unbosque.bloomtrade.dashboard.dto.IntradayBar;
import co.edu.unbosque.bloomtrade.dashboard.web.DashboardMapper;
import co.edu.unbosque.bloomtrade.integration.alpaca.CachedMarketDataAdapter;
import co.edu.unbosque.bloomtrade.portfolio.service.PortfolioService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests unit del {@link DashboardService} con mocks de los 4 dependencies (HU-F18 Lote B).
 *
 * <p>Usa el {@link DashboardMapper} real (no mockeado) porque los cálculos del mapper son
 * parte de la integración natural del flujo — separarlos en otro mock complicaría las
 * aserciones sin agregar valor. {@code DashboardMapperTest} cubre el mapper aislado.
 *
 * <p>{@link AllowedTickers} es estático e inmutable — se usa tal cual (25 tickers reales).
 */
@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock private CachedMarketDataAdapter cachedMarketDataAdapter;
    @Mock private BarsOrchestrator barsOrchestrator;
    @Mock private PortfolioService portfolioService;

    private DashboardService service;

    @BeforeEach
    void setup() {
        DashboardMapper mapper = new DashboardMapper(); // real, sin mockear
        service =
                new DashboardService(
                        cachedMarketDataAdapter, barsOrchestrator, portfolioService, mapper);
    }

    private static AccountEquityDto someEquity() {
        return new AccountEquityDto("5000.00", "0.00", "5000.00", "0.00", "0.00", null, "USD");
    }

    private Map<String, BigDecimal> populatedPrices(BigDecimal value) {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        AllowedTickers.byMarket().values().forEach(list -> list.forEach(t -> map.put(t, value)));
        return map;
    }

    private Map<String, BigDecimal> nullPrices() {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        AllowedTickers.byMarket().values().forEach(list -> list.forEach(t -> map.put(t, null)));
        return map;
    }

    private Map<String, List<IntradayBar>> emptyBars() {
        Map<String, List<IntradayBar>> map = new HashMap<>();
        AllowedTickers.byMarket().values().forEach(list -> list.forEach(t -> map.put(t, List.of())));
        return map;
    }

    @Test
    void getSnapshot_happy_allPricesPopulated_marketDataAvailableTrue() {
        when(cachedMarketDataAdapter.getLatestPrices(any()))
                .thenReturn(populatedPrices(new BigDecimal("100.00")));
        when(barsOrchestrator.fetchBars(any())).thenReturn(emptyBars());
        when(portfolioService.getAccountEquity(any(), any())).thenReturn(someEquity());

        DashboardSnapshotResponse response = service.getSnapshot(USER_ID);

        assertThat(response.marketDataAvailable()).isEqualTo("true");
        assertThat(response.tickers()).hasSize(5); // NYSE NASDAQ LSE TSE ASX
        assertThat(response.tickers().get(0).items()).hasSize(5);
        assertThat(response.equity()).isEqualTo(someEquity());
        assertThat(response.fetchedAt()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void getSnapshot_allPricesNull_marketDataAvailableFalse() {
        when(cachedMarketDataAdapter.getLatestPrices(any())).thenReturn(nullPrices());
        when(barsOrchestrator.fetchBars(any())).thenReturn(emptyBars());
        when(portfolioService.getAccountEquity(any(), any())).thenReturn(someEquity());

        DashboardSnapshotResponse response = service.getSnapshot(USER_ID);

        assertThat(response.marketDataAvailable()).isEqualTo("false");
        assertThat(response.tickers().get(0).items().get(0).currentPrice()).isNull();
    }

    @Test
    void getSnapshot_somePricesNull_marketDataAvailablePartial() {
        Map<String, BigDecimal> prices = populatedPrices(new BigDecimal("100.00"));
        // Nullear 3 precios de 25 → partial
        prices.put("AAPL", null);
        prices.put("MSFT", null);
        prices.put("TSLA", null);
        when(cachedMarketDataAdapter.getLatestPrices(any())).thenReturn(prices);
        when(barsOrchestrator.fetchBars(any())).thenReturn(emptyBars());
        when(portfolioService.getAccountEquity(any(), any())).thenReturn(someEquity());

        DashboardSnapshotResponse response = service.getSnapshot(USER_ID);

        assertThat(response.marketDataAvailable()).isEqualTo("partial");
    }

    @Test
    void getSnapshot_groupsInCorrectOrder_NYSE_NASDAQ_LSE_TSE_ASX() {
        when(cachedMarketDataAdapter.getLatestPrices(any()))
                .thenReturn(populatedPrices(new BigDecimal("100.00")));
        when(barsOrchestrator.fetchBars(any())).thenReturn(emptyBars());
        when(portfolioService.getAccountEquity(any(), any())).thenReturn(someEquity());

        DashboardSnapshotResponse response = service.getSnapshot(USER_ID);

        List<String> markets = response.tickers().stream().map(g -> g.market()).toList();
        assertThat(markets).containsExactly("NYSE", "NASDAQ", "LSE", "TSE", "ASX");
    }

    @Test
    void getSnapshot_invokesPortfolioServiceWithSamePricesMap() {
        Map<String, BigDecimal> prices = populatedPrices(new BigDecimal("150.00"));
        when(cachedMarketDataAdapter.getLatestPrices(any())).thenReturn(prices);
        when(barsOrchestrator.fetchBars(any())).thenReturn(emptyBars());
        // Devuelve un equity arbitrario — el test verifica que se llama con los prices correctos.
        when(portfolioService.getAccountEquity(USER_ID, prices))
                .thenReturn(new AccountEquityDto("100.00", null, null, null, null, null, "USD"));

        DashboardSnapshotResponse response = service.getSnapshot(USER_ID);

        assertThat(response.equity().balance()).isEqualTo("100.00");
        // No verify count — confiamos en que mockito requeriría stubbing exacto si no se llamó.
    }

    @Test
    void getSnapshot_emptyBarsForAllTickers_sparklinesAllEmpty() {
        when(cachedMarketDataAdapter.getLatestPrices(any()))
                .thenReturn(populatedPrices(new BigDecimal("100.00")));
        when(barsOrchestrator.fetchBars(any())).thenReturn(emptyBars());
        when(portfolioService.getAccountEquity(any(), any())).thenReturn(someEquity());

        DashboardSnapshotResponse response = service.getSnapshot(USER_ID);

        response.tickers()
                .forEach(group -> group.items().forEach(t -> assertThat(t.sparkline()).isEmpty()));
        // Pero el currentPrice sigue presente (los precios spot no dependen de bars).
        assertThat(response.tickers().get(0).items().get(0).currentPrice()).isEqualTo("100.00");
    }
}
