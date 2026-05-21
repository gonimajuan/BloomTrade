package co.edu.unbosque.bloomtrade.auth.profile.controller;

import co.edu.unbosque.bloomtrade.auth.profile.dto.UpdateProfileRequest;
import co.edu.unbosque.bloomtrade.auth.profile.dto.UserProfileResponse;
import co.edu.unbosque.bloomtrade.auth.profile.service.ProfileService;
import co.edu.unbosque.bloomtrade.auth.security.AuthenticatedUser;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Perfil del usuario autenticado (spec HU-F04+F20 §6.1).
 *
 * <p>El {@code userId} se resuelve <strong>únicamente</strong> desde el {@link AuthenticatedUser}
 * del {@code SecurityContextHolder} — nunca desde path, body o query (D9, constraint §10.2 anti
 * path-tampering). El {@code JwtAuthenticationFilter} ya rechazó el request con 401 si el JWT no
 * es válido.
 */
@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Profile")
public class MeController {

    private final ProfileService profileService;

    public MeController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @Operation(
            summary = "Obtiene el perfil completo del usuario autenticado",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Perfil del usuario autenticado",
                content = @Content(schema = @Schema(implementation = UserProfileResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "No autenticado, token expirado o inválido",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<UserProfileResponse> getMe(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(profileService.getMe(principal.userId()));
    }

    @Operation(
            summary = "Actualiza parcialmente el perfil del usuario autenticado",
            description =
                    "PATCH semántico: sólo los campos presentes en el payload se modifican. "
                            + "Campos read-only (email, rol, etc.) son rechazados con 400. "
                            + "Si ningún campo cambia efectivamente, responde 200 sin emitir "
                            + "evento de auditoría (D17 idempotencia).",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Perfil actualizado",
                content = @Content(schema = @Schema(implementation = UserProfileResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description =
                        "Validación falló (READ_ONLY_FIELD_MODIFIED, INVALID_TICKER, "
                                + "TOO_MANY_TICKERS, DUPLICATE_TICKERS, VALIDATION_*)",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "No autenticado",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "500",
                description = "Error técnico",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping
    public ResponseEntity<UserProfileResponse> patchMe(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody UpdateProfileRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
                profileService.updateMe(principal.userId(), request, clientIp(httpRequest)));
    }

    /** IP de origen para auditoría (ARCHITECTURE.md §12) — mismo patrón que LoginController. */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
