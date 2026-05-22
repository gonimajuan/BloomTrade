package co.edu.unbosque.bloomtrade.auth.subscription.controller;

import co.edu.unbosque.bloomtrade.auth.subscription.service.StripeWebhookHandler;
import co.edu.unbosque.bloomtrade.shared.web.ErrorResponse;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint de webhooks de Stripe (HU-F06 §6.1.4). Autentica por HMAC del header
 * {@code Stripe-Signature}, NO por JWT (exento en {@code SecurityConfig}).
 *
 * <p><strong>Raw body crítico:</strong> el {@code @RequestBody String} preserva bytes exactos
 * — la firma HMAC se calcula sobre el body raw, cualquier deserialización Jackson previa
 * cambiaría el hash y romper la verificación. NO usar {@code @RequestBody Event} ni similar.
 */
@RestController
@Tag(name = "Webhooks")
public class StripeWebhookController {

    private final StripeWebhookHandler handler;

    public StripeWebhookController(StripeWebhookHandler handler) {
        this.handler = handler;
    }

    @Operation(
            summary = "Endpoint de webhooks de Stripe (idempotente)",
            description =
                    "Recibe eventos de Stripe (checkout.session.completed, "
                            + "customer.subscription.updated/deleted, invoice.payment_failed). "
                            + "Autoriza por HMAC del header Stripe-Signature. Idempotente: el "
                            + "mismo stripe_event_id procesado N veces produce 1 PROCESSED + "
                            + "(N-1) DUPLICATE. Stripe NO reintenta sobre 400; SÍ reintenta sobre 5xx.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Evento procesado o duplicado ignorado"),
        @ApiResponse(
                responseCode = "400",
                description = "Firma inválida o body malformado",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "500",
                description = "Error durante procesamiento — Stripe reintentará",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @Hidden // No expuesto en Swagger UI público — endpoint solo para Stripe.
    @PostMapping(value = "/api/v1/webhooks/stripe", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> receive(
            @RequestBody String rawBody,
            @RequestHeader("Stripe-Signature") String signature,
            HttpServletRequest httpRequest) {
        handler.handle(rawBody, signature, clientIp(httpRequest));
        return ResponseEntity.ok().build();
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
