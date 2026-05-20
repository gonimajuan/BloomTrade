package co.edu.unbosque.bloomtrade.auth.controller;

import co.edu.unbosque.bloomtrade.auth.dto.MfaResendRequest;
import co.edu.unbosque.bloomtrade.auth.dto.MfaResendResponse;
import co.edu.unbosque.bloomtrade.auth.dto.MfaVerifyRequest;
import co.edu.unbosque.bloomtrade.auth.dto.MfaVerifyResponse;
import co.edu.unbosque.bloomtrade.auth.service.MfaService;
import co.edu.unbosque.bloomtrade.shared.web.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints públicos del paso 2 del flujo MFA (spec HU-F02 §6.1.2 y §6.1.3). */
@RestController
@RequestMapping("/api/v1/auth/mfa")
@Tag(name = "Authentication")
public class MfaController {

    private final MfaService mfaService;

    public MfaController(MfaService mfaService) {
        this.mfaService = mfaService;
    }

    @Operation(
            summary = "Verifica el código OTP y emite el access token",
            description =
                    "Paso 2 del flujo MFA. Si el OTP coincide, emite un access token JWT (15 min) "
                            + "y devuelve datos básicos del usuario. La sesión temporal y el OTP "
                            + "se eliminan inmediatamente (OTP de un solo uso). Decisión D18: "
                            + "no se entrega refresh token ni cookie en este bundle.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "OTP válido, access token emitido",
                content = @Content(schema = @Schema(implementation = MfaVerifyResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Código inválido, expirado o validación de formato falló",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "Sesión temporal expirada o inexistente",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "403",
                description = "Demasiados intentos: sesión invalidada",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "500",
                description = "Error técnico inesperado",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/verify")
    public ResponseEntity<MfaVerifyResponse> verify(
            @Valid @RequestBody MfaVerifyRequest request, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(mfaService.verify(request, clientIp(httpRequest)));
    }

    @Operation(
            summary = "Reenvía el código OTP a una sesión temporal activa",
            description =
                    "Genera un OTP nuevo y lo despacha por email. Aplica cooldown de 30s entre "
                            + "reenvíos y máximo de 3 reenvíos por sesión temporal.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Nuevo OTP enviado",
                content = @Content(schema = @Schema(implementation = MfaResendResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Validación de formato falló",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "Sesión temporal expirada o inexistente",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "429",
                description =
                        "Cooldown activo (Retry-After) o máximo de reenvíos alcanzado",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "500",
                description = "Error técnico inesperado",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/resend")
    public ResponseEntity<MfaResendResponse> resend(
            @Valid @RequestBody MfaResendRequest request, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(mfaService.resend(request, clientIp(httpRequest)));
    }

    /** IP de origen para auditoría (ARCHITECTURE.md §12): primer salto de X-Forwarded-For. */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
