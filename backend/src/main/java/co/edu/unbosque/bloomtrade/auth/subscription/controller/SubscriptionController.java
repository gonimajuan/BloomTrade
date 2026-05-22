package co.edu.unbosque.bloomtrade.auth.subscription.controller;

import co.edu.unbosque.bloomtrade.auth.security.AuthenticatedUser;
import co.edu.unbosque.bloomtrade.auth.subscription.dto.CheckoutSessionRequest;
import co.edu.unbosque.bloomtrade.auth.subscription.dto.CheckoutSessionResponse;
import co.edu.unbosque.bloomtrade.auth.subscription.dto.PortalSessionResponse;
import co.edu.unbosque.bloomtrade.auth.subscription.dto.SubscriptionStatusResponse;
import co.edu.unbosque.bloomtrade.auth.subscription.service.SubscriptionService;
import co.edu.unbosque.bloomtrade.shared.web.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints síncronos de gestión de suscripciones (HU-F06 §6.1).
 *
 * <p>El userId se resuelve <strong>únicamente</strong> desde {@link AuthenticatedUser} en el
 * {@code SecurityContextHolder} — nunca desde path, body o query (D9 HU-F04 anti
 * path-tampering, replicado en HU-F06).
 */
@RestController
@RequestMapping("/api/v1/subscriptions")
@Tag(name = "Subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @Operation(
            summary = "Inicia el flujo de compra de suscripción premium",
            description =
                    "Crea una Checkout Session en Stripe (mode=subscription) y devuelve la URL "
                            + "hosted donde el frontend debe redirigir al usuario. Si el usuario "
                            + "no tenía stripe_customer_id, se le crea uno en Stripe.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Checkout Session creada",
                content =
                        @Content(
                                schema =
                                        @Schema(implementation = CheckoutSessionResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Plan inválido",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "No autenticado",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "409",
                description = "El usuario ya tiene una suscripción activa",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "502",
                description = "Stripe API no responde",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/checkout-session")
    public ResponseEntity<CheckoutSessionResponse> createCheckoutSession(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CheckoutSessionRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
                subscriptionService.createCheckoutSession(
                        principal.userId(), request.plan(), clientIp(httpRequest)));
    }

    @Operation(
            summary = "Estado de suscripción del usuario autenticado",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Estado de suscripción",
                content =
                        @Content(
                                schema =
                                        @Schema(
                                                implementation =
                                                        SubscriptionStatusResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "No autenticado",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/me")
    public ResponseEntity<SubscriptionStatusResponse> getMe(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(subscriptionService.getStatus(principal.userId()));
    }

    @Operation(
            summary = "Inicia sesión del Customer Portal de Stripe",
            description =
                    "Genera una URL hosted del Customer Portal donde el usuario puede cancelar, "
                            + "reactivar, actualizar tarjeta y ver invoices (HU-F06 v1.2).",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Portal Session creada",
                content = @Content(schema = @Schema(implementation = PortalSessionResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "No autenticado",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "409",
                description = "El usuario no tiene Customer en Stripe (nunca pasó por checkout)",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "502",
                description = "Stripe API no responde",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/portal-session")
    public ResponseEntity<PortalSessionResponse> openBillingPortal(
            @AuthenticationPrincipal AuthenticatedUser principal,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
                subscriptionService.openBillingPortal(
                        principal.userId(), clientIp(httpRequest)));
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
