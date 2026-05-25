package co.edu.unbosque.bloomtrade.dashboard.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Agrupación de tickers por mercado (HU-F18 §6.2 SPEC). El orden de inserción
 * NYSE → NASDAQ → LSE → TSE → ASX (oeste a este por timezone) viene del
 * {@code AllowedTickers.byMarket()} (LinkedHashMap inmutable) y se preserva en la respuesta.
 */
@Schema(description = "Grupo de tickers de un mercado (5 por mercado en MVP).")
public record MarketGroupDto(
        @Schema(
                        allowableValues = {"NYSE", "NASDAQ", "LSE", "TSE", "ASX"},
                        example = "NYSE")
                String market,
        List<TickerDashboardDto> items) {}
