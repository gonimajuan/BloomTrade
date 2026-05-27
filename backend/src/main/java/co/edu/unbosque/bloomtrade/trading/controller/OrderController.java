package co.edu.unbosque.bloomtrade.trading.controller;

import co.edu.unbosque.bloomtrade.auth.security.AuthenticatedUser;
import co.edu.unbosque.bloomtrade.trading.dto.OrderResponse;
import co.edu.unbosque.bloomtrade.trading.dto.PlaceOrderRequest;
import co.edu.unbosque.bloomtrade.trading.dto.QuoteRequest;
import co.edu.unbosque.bloomtrade.trading.dto.QuoteResponse;
import co.edu.unbosque.bloomtrade.trading.service.PlaceOrderResult;
import co.edu.unbosque.bloomtrade.trading.service.TradingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints REST de órdenes (HU-F09 §6).
 *
 * <ul>
 *   <li>{@code POST /api/v1/orders/quote} — informativo, sin persistencia ni efectos.</li>
 *   <li>{@code POST /api/v1/orders} — crear y ejecutar orden Market. Idempotente por
 *       {@code clientOrderId}. Devuelve 201 (nuevo) o 200 (idempotente).</li>
 * </ul>
 *
 * <p>El {@code userId} se resuelve desde {@link AuthenticatedUser} (vía Spring Security
 * principal) — NUNCA del body/query/path. Garantiza que un usuario no pueda operar por otro
 * (SPEC §10.2 constraint NO negociable).
 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Operaciones de trading — quote y ejecución de órdenes Market")
public class OrderController {

    private final TradingService tradingService;

    public OrderController(TradingService tradingService) {
        this.tradingService = tradingService;
    }

    @PostMapping("/quote")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Quote informativo previo a confirmación de orden",
            description =
                    "Calcula precio actual + comisión + total + estado de fondos sin persistencia."
                            + " La comisión se redondea HALF_UP a 2 decimales (ARCH §9). El"
                            + " precio del quote es referencial — el de ejecución puede diferir"
                            + " por slippage (sin tolerancia configurable en MVP, D4).")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Quote calculado",
                content = @Content(schema = @Schema(implementation = QuoteResponse.class))),
        @ApiResponse(responseCode = "400", description = "Ticker / quantity / side inválidos"),
        @ApiResponse(responseCode = "401", description = "No autenticado"),
        @ApiResponse(responseCode = "403", description = "Cuenta no activa"),
        @ApiResponse(responseCode = "502", description = "Alpaca Data no disponible")
    })
    public ResponseEntity<QuoteResponse> quote(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody QuoteRequest request) {
        QuoteResponse response = tradingService.quote(principal.userId(), request);
        return ResponseEntity.ok(response);
    }

    @PostMapping
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Crear y ejecutar una orden Market",
            description =
                    "Transaccional: lock pessimistic sobre balance, INSERT order PENDING, submit"
                            + " Alpaca, polling corto si accepted, UPDATE order a"
                            + " EXECUTED/REJECTED/FAILED, débito + upsert posición si exitosa."
                            + " Idempotente por clientOrderId: misma UUID enviada N veces produce"
                            + " 1 orden (devuelve 200, no 201, en la 2da-Nº-ésima).")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Orden creada y ejecutada",
                content = @Content(schema = @Schema(implementation = OrderResponse.class))),
        @ApiResponse(
                responseCode = "200",
                description = "Idempotente: orden ya existía con ese clientOrderId",
                content = @Content(schema = @Schema(implementation = OrderResponse.class))),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "401", description = "No autenticado"),
        @ApiResponse(responseCode = "403", description = "Cuenta no activa"),
        @ApiResponse(responseCode = "409", description = "Saldo insuficiente"),
        @ApiResponse(responseCode = "422", description = "Alpaca rechazó la orden"),
        @ApiResponse(
                responseCode = "502",
                description = "Alpaca trading o data no disponibles tras retries")
    })
    public ResponseEntity<OrderResponse> placeOrder(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody PlaceOrderRequest request) {
        PlaceOrderResult result = tradingService.placeOrder(principal.userId(), request);
        HttpStatus status = result.isNew() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.response());
    }

    // ─── HU-F15 — Cancelar orden Market ────────────────────────────────────

    @PostMapping("/{id}/cancel")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Cancelar orden Market en cola",
            description =
                    "Cancela una orden con status=PENDING + alpacaOrderId. Solicita DELETE a Alpaca"
                            + " y polea hasta 2s por confirmación. Respuestas posibles:"
                            + " 200 con status=CANCELED + refundedAmount/restoredQty (polling-OK);"
                            + " 200 con status=PENDING + cancelRequestedAt (polling-timeout, reconcile"
                            + " lazy v2 materializará); 200 con status=EXECUTED (race-filled durante"
                            + " polling); 404 ORDER_NOT_FOUND; 409 ORDER_NOT_CANCELABLE; 502"
                            + " BROKER_UNAVAILABLE. Idempotente por order.id: 2da llamada sobre orden"
                            + " ya cancelada devuelve 200 sin efectos.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Orden cancelada / cancel solicitado / race-filled",
                content = @Content(schema = @Schema(implementation = OrderResponse.class))),
        @ApiResponse(responseCode = "401", description = "No autenticado"),
        @ApiResponse(responseCode = "403", description = "Cuenta no activa"),
        @ApiResponse(responseCode = "404", description = "ORDER_NOT_FOUND (defensa anti-enumeración)"),
        @ApiResponse(responseCode = "409", description = "ORDER_NOT_CANCELABLE (estado terminal)"),
        @ApiResponse(responseCode = "502", description = "BROKER_UNAVAILABLE (Alpaca down)")
    })
    public ResponseEntity<OrderResponse> cancelOrder(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable("id") UUID id) {
        OrderResponse response = tradingService.cancelOrder(principal.userId(), id);
        return ResponseEntity.ok(response);
    }
}
