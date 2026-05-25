package co.edu.unbosque.bloomtrade.dashboard.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Snapshot de un ticker individual en el dashboard (HU-F18 §6.2 SPEC).
 *
 * <p>Todos los campos numéricos son BigDecimal stringificado con scale=2 HALF_UP (plan D13).
 * Si Alpaca data API falla para este ticker, {@code currentPrice} es null y, por cascada,
 * también lo son {@code openPrice} y {@code dayChangePct}. {@code sparkline} es una lista
 * vacía cuando no hay bars disponibles (mercado cerrado, ticker delisted, fallo del adapter).
 */
@Schema(description = "Snapshot intradía de un ticker — precio actual, variación día y sparkline.")
public record TickerDashboardDto(
        @Schema(example = "AAPL") String ticker,
        @Schema(
                        description = "Mid-price actual. null si Alpaca falló para este ticker.",
                        example = "193.20",
                        nullable = true)
                String currentPrice,
        @Schema(
                        description =
                                "Precio de apertura intradía (close de la primera barra del día).",
                        example = "189.50",
                        nullable = true)
                String openPrice,
        @Schema(
                        description =
                                "Variación porcentual desde la apertura: ((current − open) / open) × 100.",
                        example = "1.95",
                        nullable = true)
                String dayChangePct,
        @Schema(
                        description =
                                "Serie cronológica de closes intradía (15Min) en string scale=2. Vacía si bars no disponibles.")
                List<String> sparkline) {}
