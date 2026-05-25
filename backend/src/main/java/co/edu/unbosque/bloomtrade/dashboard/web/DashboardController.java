package co.edu.unbosque.bloomtrade.dashboard.web;

import co.edu.unbosque.bloomtrade.auth.security.AuthenticatedUser;
import co.edu.unbosque.bloomtrade.dashboard.dto.DashboardSnapshotResponse;
import co.edu.unbosque.bloomtrade.dashboard.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint REST del módulo Dashboard (HU-F18 Lote B).
 *
 * <p>Plan D-AUDIT-EVENTS: NO se emiten audit events para reads (consistente con F16+F21).
 * Plan D23: usa {@code AuthenticatedUser} record, NO {@code User} entity JPA — convención
 * BloomTrade registrada en memory {@code feedback_authenticatedprincipal_no_user.md}.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Dashboard", description = "Snapshot del dashboard: 25 tickers + equity del usuario")
@SecurityRequirement(name = "bearerAuth")
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/snapshot")
    @Operation(
            summary = "Snapshot consolidado del dashboard",
            description =
                    "Devuelve 25 tickers agrupados por mercado (NYSE/NASDAQ/LSE/TSE/ASX) con"
                            + " precio actual, variación intradía y sparkline (cache Redis TTL"
                            + " 30s); más el equity total del usuario y P&L no realizado vs"
                            + " cost basis. Si Alpaca falla para algunos tickers,"
                            + " marketDataAvailable degrada a 'partial' o 'false' (HU-F16 D2 D3"
                            + " semántica reutilizada). Polling recomendado del cliente: cada"
                            + " 30s.")
    @ApiResponse(responseCode = "200", description = "Snapshot retornado (puede ser degradado)")
    @ApiResponse(responseCode = "401", description = "JWT ausente, inválido o expirado")
    public DashboardSnapshotResponse getSnapshot(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        long startNanos = System.nanoTime();
        UUID userId = principal.userId();
        DashboardSnapshotResponse response = dashboardService.getSnapshot(userId);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        log.info(
                "GET /dashboard/snapshot userId={} marketDataAvailable={} elapsedMs={}",
                userId,
                response.marketDataAvailable(),
                elapsedMs);
        return response;
    }
}
