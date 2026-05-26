package co.edu.unbosque.bloomtrade.config;

import co.edu.unbosque.bloomtrade.shared.web.ErrorResponse;
import co.edu.unbosque.bloomtrade.shared.web.TraceIdFilter;
import co.edu.unbosque.bloomtrade.shared.web.ValidationMessages;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Emite {@code 401 Unauthorized} con cuerpo {@code {error: AUTH_REQUIRED, ...}} cuando un
 * request anónimo intenta acceder a un endpoint protegido.
 *
 * <p>Cierra la deuda viva cross-cutting D17 (HU-F16+F21) + D-T5.2 (HU-F18) registrada como
 * mini-HU {@code HU-F0X-token-rotation-logout}: sin este bean, Spring Security 6 respondía
 * {@code 403 Forbidden} por default cuando no había {@code Authorization} header,
 * divergiendo del contrato de 401 declarado en cada SPEC.
 *
 * <p>El {@link JwtAuthenticationFilter} sigue emitiendo {@code TOKEN_EXPIRED} /
 * {@code TOKEN_INVALID} directamente cuando viene el JWT pero es inválido; este entrypoint
 * solo cubre el caso "sin Authorization header" (anonimo intentando acceder a recurso
 * protegido).
 */
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public JwtAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        ErrorResponse body =
                ErrorResponse.of(
                        HttpServletResponse.SC_UNAUTHORIZED,
                        "AUTH_REQUIRED",
                        ValidationMessages.humanFor("AUTH_REQUIRED"),
                        request.getRequestURI(),
                        TraceIdFilter.currentTraceId());
        objectMapper.writeValue(response.getWriter(), body);
    }
}
