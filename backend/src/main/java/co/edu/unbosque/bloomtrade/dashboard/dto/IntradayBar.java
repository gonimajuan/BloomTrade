package co.edu.unbosque.bloomtrade.dashboard.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Barra OHLCV intradía en el modelo del dominio Dashboard (HU-F18 Lote A).
 *
 * <p>Equivalente conceptual a {@code AlpacaBar} pero desacoplado del DTO Jackson externo:
 * el {@code MarketDataAdapter.getIntradayBars} mapea de {@code AlpacaBar} a {@code IntradayBar}
 * para que {@code DashboardService} y {@code DashboardMapper} no dependan de tipos Alpaca-specific.
 *
 * <p>NO se serializa al cliente — solo es consumido internamente por el mapper que produce
 * los sparklines (lista de closes stringificados scale=2).
 */
public record IntradayBar(
        Instant timestamp,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        Long volume) {}
