package co.edu.unbosque.bloomtrade.dashboard.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Equity total de la cuenta del usuario (HU-F18 §6.2 SPEC, plan C7).
 *
 * <p>Todos los campos numéricos son BigDecimal stringificado scale=2 HALF_UP (plan D13).
 * Semántica null:
 * <ul>
 *   <li>{@code balance} — nunca null (siempre viene de {@code app.user_balances}).</li>
 *   <li>{@code positionsMarketValue} — null cuando Alpaca falló para TODAS las posiciones del
 *       usuario; sum parcial cuando algunas fallaron (plan D-EQUITY-PARTIAL).</li>
 *   <li>{@code equity} — {@code balance + positionsMarketValue}. null si market value es null.</li>
 *   <li>{@code costBasisTotal} — null si no hay posiciones.</li>
 *   <li>{@code unrealizedPnL} — {@code marketValue − costBasis}. null si cualquiera es null.</li>
 *   <li>{@code unrealizedPnLPct} — {@code (pnl / costBasis) × 100}. null si costBasis es 0 o
 *       pnl es null.</li>
 * </ul>
 */
@Schema(description = "Equity total y P&L no realizado del usuario.")
public record AccountEquityDto(
        @Schema(example = "5234.45") String balance,
        @Schema(example = "3974.50", nullable = true) String positionsMarketValue,
        @Schema(example = "9208.95", nullable = true) String equity,
        @Schema(example = "3954.50", nullable = true) String costBasisTotal,
        @Schema(example = "20.00", nullable = true) String unrealizedPnL,
        @Schema(example = "0.51", nullable = true) String unrealizedPnLPct,
        @Schema(allowableValues = {"USD"}, example = "USD") String currency) {}
