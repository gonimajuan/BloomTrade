package co.edu.unbosque.bloomtrade.dashboard.service;

import co.edu.unbosque.bloomtrade.auth.profile.catalog.AllowedTickers;
import co.edu.unbosque.bloomtrade.auth.profile.domain.Market;
import co.edu.unbosque.bloomtrade.dashboard.dto.AccountEquityDto;
import co.edu.unbosque.bloomtrade.dashboard.dto.DashboardSnapshotResponse;
import co.edu.unbosque.bloomtrade.dashboard.dto.IntradayBar;
import co.edu.unbosque.bloomtrade.dashboard.dto.MarketGroupDto;
import co.edu.unbosque.bloomtrade.dashboard.dto.TickerDashboardDto;
import co.edu.unbosque.bloomtrade.dashboard.web.DashboardMapper;
import co.edu.unbosque.bloomtrade.integration.alpaca.CachedMarketDataAdapter;
import co.edu.unbosque.bloomtrade.portfolio.service.PortfolioService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Servicio orquestador del módulo Dashboard (HU-F18 Lote B — plan D8).
 *
 * <p>Compone tres fuentes en una sola respuesta consolidada:
 * <ol>
 *   <li>Catálogo: {@code AllowedTickers.byMarket()} (5 mercados × 5 tickers, orden de inserción
 *       NYSE → NASDAQ → LSE → TSE → ASX).</li>
 *   <li>Precios spot: {@link CachedMarketDataAdapter#getLatestPrices} (cache Redis TTL 30s
 *       amortigua el polling 30s/usuario).</li>
 *   <li>Bars intradía: {@link BarsOrchestrator#fetchBars} (V1 sin cache; deuda D-SPARKLINE-CACHE
 *       V2 si rate-limit Alpaca golpea).</li>
 * </ol>
 *
 * <p>El equity del usuario se delega a {@link PortfolioService#getAccountEquity} (plan D9 —
 * coherencia con {@code getBalance}/{@code getPositions} read-only del módulo Portfolio).
 *
 * <p>{@code marketDataAvailable} consolidado: solo cuenta nulls de PRECIOS (sparklines vacías
 * NO contaminan el flag — pueden estar vacías legítimamente en weekends/holidays).
 */
@Service
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    private final CachedMarketDataAdapter cachedMarketDataAdapter;
    private final BarsOrchestrator barsOrchestrator;
    private final PortfolioService portfolioService;
    private final DashboardMapper dashboardMapper;

    public DashboardService(
            CachedMarketDataAdapter cachedMarketDataAdapter,
            BarsOrchestrator barsOrchestrator,
            PortfolioService portfolioService,
            DashboardMapper dashboardMapper) {
        this.cachedMarketDataAdapter = cachedMarketDataAdapter;
        this.barsOrchestrator = barsOrchestrator;
        this.portfolioService = portfolioService;
        this.dashboardMapper = dashboardMapper;
    }

    public DashboardSnapshotResponse getSnapshot(UUID userId) {
        Map<Market, List<String>> byMarket = AllowedTickers.byMarket();
        List<String> allTickers = new ArrayList<>();
        for (List<String> ts : byMarket.values()) {
            allTickers.addAll(ts);
        }

        // Precios spot con cache. Tickers fallidos quedan con value=null en el map.
        Map<String, BigDecimal> prices = cachedMarketDataAdapter.getLatestPrices(allTickers);

        // Bars intradía sin cache (V1). Tickers fallidos quedan con List.of() en el map.
        Map<String, List<IntradayBar>> barsMap = barsOrchestrator.fetchBars(allTickers);

        // Equity del usuario sobre los mismos prices (reusa los nulls coherentemente).
        AccountEquityDto equity = portfolioService.getAccountEquity(userId, prices);

        // Mapeo agrupado por mercado, preservando el orden NYSE → ASX de AllowedTickers.
        List<MarketGroupDto> groups = new ArrayList<>(byMarket.size());
        for (Map.Entry<Market, List<String>> e : byMarket.entrySet()) {
            List<TickerDashboardDto> items = new ArrayList<>(e.getValue().size());
            for (String ticker : e.getValue()) {
                items.add(
                        dashboardMapper.toTickerDashboardDto(
                                ticker, prices.get(ticker), barsMap.get(ticker)));
            }
            groups.add(dashboardMapper.toMarketGroupDto(e.getKey(), items));
        }

        String marketDataAvailable = computeAvailability(prices, allTickers.size());
        log.debug(
                "Dashboard snapshot userId={} marketDataAvailable={}",
                userId,
                marketDataAvailable);
        return dashboardMapper.toDashboardSnapshotResponse(
                groups, equity, marketDataAvailable, Instant.now());
    }

    /**
     * Plan D-MARKET-DATA-AVAILABILITY: cuenta nulls de PRECIOS. Sparklines vacías no influyen
     * (pueden ser legítimas en weekends/holidays sin que Alpaca esté caído).
     */
    private String computeAvailability(Map<String, BigDecimal> prices, int totalTickers) {
        long nulls = prices.values().stream().filter(v -> v == null).count();
        if (nulls == 0) {
            return "true";
        }
        if (nulls == totalTickers) {
            return "false";
        }
        return "partial";
    }
}
