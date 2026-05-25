package co.edu.unbosque.bloomtrade.portfolio.web;

import co.edu.unbosque.bloomtrade.auth.security.AuthenticatedUser;
import co.edu.unbosque.bloomtrade.portfolio.domain.Position;
import co.edu.unbosque.bloomtrade.portfolio.domain.UserBalance;
import co.edu.unbosque.bloomtrade.portfolio.dto.BalanceResponse;
import co.edu.unbosque.bloomtrade.portfolio.dto.PortfolioPositionsResponse;
import co.edu.unbosque.bloomtrade.portfolio.service.MarketDataOrchestrator;
import co.edu.unbosque.bloomtrade.portfolio.service.PortfolioService;
import co.edu.unbosque.bloomtrade.trading.domain.Order;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints REST del módulo Portfolio (HU-F16 + HU-F21, plan D5).
 *
 * <p>Ambos endpoints son autenticados por JWT (delegado al {@code JwtAuthenticationFilter}
 * vía {@code SecurityConfig.anyRequest().authenticated()}). El filtro popula
 * {@link AuthenticationPrincipal} con el {@link User} resuelto desde el token. Sin JWT o
 * con JWT expirado → 401 {@code AUTHENTICATION_REQUIRED} antes de llegar al controller.
 *
 * <p>Plan D9: NO se emiten audit events para reads (consistente con {@code /me} F04 y
 * {@code /subscription/status} F06).
 */
@RestController
@RequestMapping("/api/v1/portfolio")
@Tag(name = "Portfolio", description = "Consulta de portafolio y saldo del inversionista")
@SecurityRequirement(name = "bearerAuth")
public class PortfolioController {

    private static final Logger log = LoggerFactory.getLogger(PortfolioController.class);

    private final PortfolioService portfolioService;
    private final MarketDataOrchestrator marketDataOrchestrator;
    private final PortfolioMapper mapper;

    public PortfolioController(
            PortfolioService portfolioService,
            MarketDataOrchestrator marketDataOrchestrator,
            PortfolioMapper mapper) {
        this.portfolioService = portfolioService;
        this.marketDataOrchestrator = marketDataOrchestrator;
        this.mapper = mapper;
    }

    @GetMapping("/balance")
    @Operation(
            summary = "Saldo USD disponible del usuario autenticado",
            description =
                    "Devuelve el balance actual y el instante de la última actualización"
                            + " (de app.user_balances.updated_at). HU-F21.")
    @ApiResponse(responseCode = "200", description = "Saldo recuperado correctamente")
    @ApiResponse(responseCode = "401", description = "JWT ausente, inválido o expirado")
    public BalanceResponse getBalance(@AuthenticationPrincipal AuthenticatedUser principal) {
        long startNanos = System.nanoTime();
        UUID userId = principal.userId();
        UserBalance entity = portfolioService.getBalanceEntity(userId);
        BalanceResponse response = mapper.toBalanceResponse(entity);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        log.info(
                "GET /portfolio/balance userId={} balance={} elapsedMs={}",
                userId,
                response.balance(),
                elapsedMs);
        return response;
    }

    @GetMapping("/positions")
    @Operation(
            summary = "Portafolio con mark-to-market y órdenes pendientes",
            description =
                    "Devuelve las posiciones del usuario enriquecidas con precio actual de"
                            + " Alpaca data API. Si Alpaca falla para algún ticker, esos DTOs"
                            + " tienen los campos de mercado en null y marketDataAvailable se"
                            + " marca como 'partial' o 'false' (HU-F16 plan D2, D3). Adicionalmente"
                            + " devuelve pendingOrders[] con órdenes PENDING+alpacaOrderId.")
    @ApiResponse(responseCode = "200", description = "Portafolio retornado (puede ser degradado)")
    @ApiResponse(responseCode = "401", description = "JWT ausente, inválido o expirado")
    public PortfolioPositionsResponse getPositions(@AuthenticationPrincipal AuthenticatedUser principal) {
        long startNanos = System.nanoTime();
        UUID userId = principal.userId();
        List<Position> positions = portfolioService.getPositions(userId);
        List<Order> pending = portfolioService.getPendingOrders(userId);
        Set<String> tickers =
                positions.stream().map(Position::getTicker).collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        Map<String, BigDecimal> prices = marketDataOrchestrator.fetchPrices(tickers);
        PortfolioPositionsResponse response = mapper.toPositionsResponse(positions, prices, pending);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        log.info(
                "GET /portfolio/positions userId={} positions={} pending={} marketDataAvailable={} elapsedMs={}",
                userId,
                positions.size(),
                pending.size(),
                response.marketDataAvailable(),
                elapsedMs);
        return response;
    }
}
