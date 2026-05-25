package co.edu.unbosque.bloomtrade.portfolio.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * Output de {@code GET /api/v1/portfolio/positions} (HU-F16 §6.2). Listado completo del estado
 * del portafolio enriquecido con mark-to-market y órdenes encoladas en Alpaca.
 *
 * <p>{@link #marketDataAvailable} señala el estado del fan-out a Alpaca data API:
 * <ul>
 *   <li>{@code "true"} — todas las posiciones tienen precio actual (o no hay posiciones).</li>
 *   <li>{@code "partial"} — algunas posiciones tienen los 4 campos de mercado en null.</li>
 *   <li>{@code "false"} — ninguna posición pudo enriquecerse (Alpaca data API totalmente
 *       caído tras 3 retries del adapter o timeouts agresivos del orchestrator).</li>
 * </ul>
 * El endpoint NUNCA propaga 502 al cliente — la degradación es siempre 200 OK con info
 * parcial (plan D3, NFR-AVAIL-PORTFOLIO §10).
 */
@Schema(description = "Portafolio completo del usuario con mark-to-market y órdenes pendientes")
public record PortfolioPositionsResponse(
        List<PositionDto> positions,
        @Schema(description = "Órdenes PENDING+alpacaOrderId esperando apertura de mercado")
                List<PendingOrderDto> pendingOrders,
        @Schema(
                        description =
                                "Estado del mark-to-market. Valores: true | partial | false",
                        example = "true",
                        allowableValues = {"true", "partial", "false"})
                String marketDataAvailable,
        @Schema(description = "Instante UTC de generación del response")
                Instant fetchedAt) {}
