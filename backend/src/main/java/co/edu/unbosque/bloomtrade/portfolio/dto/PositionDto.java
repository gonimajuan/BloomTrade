package co.edu.unbosque.bloomtrade.portfolio.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Ítem del array {@code positions[]} en {@link PortfolioPositionsResponse} (HU-F16 §6.2).
 * Representa una posición abierta del usuario enriquecida con mark-to-market.
 *
 * <p>Los 4 campos de mercado ({@code currentPrice}, {@code marketValue}, {@code unrealizedPnL},
 * {@code unrealizedPnLPct}) son {@code null} cuando la consulta a Alpaca data API falló para
 * el ticker (timeout 1.5s o excepción del adapter — plan D2). El frontend muestra "—" en esas
 * celdas y el banner top-level refleja la degradación vía
 * {@link PortfolioPositionsResponse#marketDataAvailable}.
 *
 * <p>Plan D10: todos los stringificados con scale=2 y {@code RoundingMode.HALF_UP}.
 * Plan D11: {@code avgCost} es el promedio puro de compras (sin commissions);
 * {@code unrealizedPnL} es bruto sin descontar commission de venta hipotética.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Posición abierta con mark-to-market opcional")
public record PositionDto(
        String ticker,
        int quantity,
        @Schema(description = "Precio promedio ponderado de compras (scale=2)", example = "189.45")
                String avgCost,
        @Schema(description = "quantity × avgCost (scale=2)", example = "1894.50") String costBasis,
        @Schema(description = "Código ISO de moneda. MVP solo USD.", example = "USD")
                String currency,
        @Schema(
                        description =
                                "Último mid-price de Alpaca data API ((ask+bid)/2) scale=2."
                                        + " null si la consulta falló o timeout.",
                        nullable = true,
                        example = "193.20")
                String currentPrice,
        @Schema(
                        description = "quantity × currentPrice (scale=2). null si currentPrice null.",
                        nullable = true,
                        example = "1932.00")
                String marketValue,
        @Schema(
                        description =
                                "marketValue − costBasis (scale=2, con signo). null si marketValue null.",
                        nullable = true,
                        example = "37.50")
                String unrealizedPnL,
        @Schema(
                        description =
                                "(unrealizedPnL / costBasis) × 100 (scale=2, con signo)."
                                        + " null si marketValue null.",
                        nullable = true,
                        example = "1.98")
                String unrealizedPnLPct) {}
