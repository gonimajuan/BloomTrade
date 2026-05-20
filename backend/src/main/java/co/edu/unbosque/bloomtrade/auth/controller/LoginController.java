package co.edu.unbosque.bloomtrade.auth.controller;

import co.edu.unbosque.bloomtrade.auth.dto.LoginRequest;
import co.edu.unbosque.bloomtrade.auth.dto.LoginResponse;
import co.edu.unbosque.bloomtrade.auth.service.LoginService;
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

/** Endpoint público del paso 1 del flujo de autenticación (spec HU-F02 §6.1.1). */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
public class LoginController {

    private final LoginService loginService;

    public LoginController(LoginService loginService) {
        this.loginService = loginService;
    }

    @Operation(
            summary = "Inicia el flujo de autenticación con email y password",
            description =
                    "Paso 1 del flujo MFA. Si las credenciales son válidas, dispara el envío del "
                            + "OTP por email y devuelve un tempSessionId opaco que el cliente "
                            + "usará en /mfa/verify. Mensajes de error genéricos para prevenir "
                            + "account enumeration.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Credenciales válidas, OTP enviado al email del usuario",
                content = @Content(schema = @Schema(implementation = LoginResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Validación de entrada falló (email malformado o campos vacíos)",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "Credenciales inválidas (email no existe o password incorrecto)",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "403",
                description = "Cuenta no activa (BLOCKED o SUSPENDED)",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "423",
                description = "Cuenta bloqueada por intentos fallidos previos",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "500",
                description = "Error técnico inesperado",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        LoginResponse response = loginService.login(request, clientIp(httpRequest));
        return ResponseEntity.ok(response);
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
