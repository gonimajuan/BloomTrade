package co.edu.unbosque.bloomtrade.trading.history.web;

import co.edu.unbosque.bloomtrade.auth.security.AuthenticatedUser;
import co.edu.unbosque.bloomtrade.shared.web.ErrorResponse;
import co.edu.unbosque.bloomtrade.shared.web.FieldErrorItem;
import co.edu.unbosque.bloomtrade.shared.web.TraceIdFilter;
import co.edu.unbosque.bloomtrade.shared.web.ValidationMessages;
import co.edu.unbosque.bloomtrade.trading.domain.Order;
import co.edu.unbosque.bloomtrade.trading.domain.OrderSide;
import co.edu.unbosque.bloomtrade.trading.history.dto.OrderHistoryResponse;
import co.edu.unbosque.bloomtrade.trading.history.service.OrderHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint REST del historial de órdenes (HU-F17 Lote C — plan D8).
 *
 * <p>Filtros opcionales: {@code ticker}, {@code side} (BUY/SELL). Paginación Spring Data
 * con {@link PageableDefault size=20, sort=submittedAt DESC}. Cap manual del {@code size}
 * a 100 (plan D21) — Spring no lo enforza por default. Cap excedido → 400
 * {@code INVALID_REQUEST_PARAMETER}.
 *
 * <p>Plan D23: usa {@code AuthenticatedUser} record, NO {@code User} entity.
 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders History", description = "Historial paginado de órdenes del inversionista")
@SecurityRequirement(name = "bearerAuth")
public class OrderHistoryController {

    private static final Logger log = LoggerFactory.getLogger(OrderHistoryController.class);
    private static final int MAX_PAGE_SIZE = 100;
    private static final String INVALID_REQUEST_PARAMETER = "INVALID_REQUEST_PARAMETER";

    private final OrderHistoryService orderHistoryService;
    private final OrderHistoryMapper mapper;

    public OrderHistoryController(
            OrderHistoryService orderHistoryService, OrderHistoryMapper mapper) {
        this.orderHistoryService = orderHistoryService;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(
            summary = "Historial paginado de órdenes",
            description =
                    "Devuelve las órdenes del usuario autenticado con filtros opcionales por"
                            + " ticker y side. Sort fijo submittedAt DESC. Paginación por"
                            + " page/size (default size=20, max=100).")
    @ApiResponse(responseCode = "200", description = "Listado paginado")
    @ApiResponse(responseCode = "400", description = "Parámetro inválido (side fuera de BUY/SELL o size > 100)")
    @ApiResponse(responseCode = "401", description = "JWT ausente, inválido o expirado")
    public ResponseEntity<?> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "Filtra por ticker exacto (case-sensitive).")
                    @RequestParam(required = false)
                    Optional<String> ticker,
            @Parameter(description = "Filtra por lado de la orden (BUY o SELL).")
                    @RequestParam(required = false)
                    Optional<OrderSide> side,
            @PageableDefault(size = 20, sort = "submittedAt", direction = Sort.Direction.DESC)
                    @Parameter(hidden = true)
                    Pageable pageable,
            HttpServletRequest request) {
        if (pageable.getPageSize() > MAX_PAGE_SIZE) {
            return ResponseEntity.badRequest()
                    .body(
                            ErrorResponse.validation(
                                    400,
                                    INVALID_REQUEST_PARAMETER,
                                    ValidationMessages.humanFor(INVALID_REQUEST_PARAMETER),
                                    request.getRequestURI(),
                                    TraceIdFilter.currentTraceId(),
                                    List.of(
                                            new FieldErrorItem(
                                                    "size",
                                                    INVALID_REQUEST_PARAMETER,
                                                    "size máximo: " + MAX_PAGE_SIZE))));
        }
        long startNanos = System.nanoTime();
        UUID userId = principal.userId();
        Page<Order> page = orderHistoryService.list(userId, ticker, side, pageable);
        OrderHistoryResponse response = mapper.toOrderHistoryResponse(page);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        log.info(
                "GET /orders userId={} ticker={} side={} returned={} totalElements={} elapsedMs={}",
                userId,
                ticker.orElse("*"),
                side.map(Enum::name).orElse("*"),
                response.content().size(),
                response.pagination().totalElements(),
                elapsedMs);
        return ResponseEntity.ok(response);
    }
}
