package co.edu.unbosque.bloomtrade.auth.controller;

import co.edu.unbosque.bloomtrade.auth.dto.RegisterRequest;
import co.edu.unbosque.bloomtrade.auth.dto.RegisterResponse;
import co.edu.unbosque.bloomtrade.auth.service.RegisterService;
import co.edu.unbosque.bloomtrade.shared.web.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoint público de registro de inversionistas (spec HU-F01 §6.1). */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
public class RegisterController {

    private final RegisterService registerService;

    public RegisterController(RegisterService registerService) {
        this.registerService = registerService;
    }

    @Operation(
            summary = "Registra un nuevo inversionista en BloomTrade",
            description = "Endpoint público. Crea el usuario (INVESTOR/ACTIVE) y su balance inicial "
                    + "USD 10,000 en una transacción; audita y envía email de bienvenida.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Usuario registrado exitosamente",
                content = @Content(schema = @Schema(implementation = RegisterResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Validación de entrada falló o términos no aceptados",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "409",
                description = "Email ya registrado",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "500",
                description = "Error técnico inesperado",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(
            @Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        RegisterResponse response =
                registerService.register(request, clientIp(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
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
