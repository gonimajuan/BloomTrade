package co.edu.unbosque.bloomtrade.dashboard.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * Respuesta del endpoint {@code GET /api/v1/dashboard/snapshot} (HU-F18 §6.2 SPEC).
 *
 * <p>{@code marketDataAvailable} sigue la misma semántica que F16 §6.2:
 * <ul>
 *   <li>{@code "true"}: todos los precios spot disponibles.</li>
 *   <li>{@code "partial"}: 0 &lt; nulls &lt; 25 (Alpaca falló para algunos tickers).</li>
 *   <li>{@code "false"}: todos los precios null (Alpaca caído).</li>
 * </ul>
 *
 * <p>{@code fetchedAt} es el {@link Instant} server-side al armar la respuesta (plan D13 F16).
 */
@Schema(description = "Snapshot completo del dashboard: 25 tickers agrupados por mercado + equity del usuario.")
public record DashboardSnapshotResponse(
        List<MarketGroupDto> tickers,
        AccountEquityDto equity,
        @Schema(allowableValues = {"true", "false", "partial"}) String marketDataAvailable,
        Instant fetchedAt) {}
