package co.edu.unbosque.bloomtrade.config;

import co.edu.unbosque.bloomtrade.auth.exception.TokenExpiredException;
import co.edu.unbosque.bloomtrade.auth.exception.TokenInvalidException;
import co.edu.unbosque.bloomtrade.auth.security.AuthenticatedUser;
import co.edu.unbosque.bloomtrade.auth.security.JwtService;
import co.edu.unbosque.bloomtrade.shared.web.ErrorResponse;
import co.edu.unbosque.bloomtrade.shared.web.TraceIdFilter;
import co.edu.unbosque.bloomtrade.shared.web.ValidationMessages;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Filtro JWT (HU-F02 D5/T1.5). Si hay header {@code Authorization: Bearer ...}, valida con
 * {@link JwtService} y popula el {@code SecurityContext} con {@link AuthenticatedUser} +
 * authority {@code ROLE_<role>}. Si el token es inválido o expirado, escribe el
 * {@link ErrorResponse} apropiado y aborta el chain (no llega al endpoint).
 *
 * <p>Si NO hay header, el filtro pasa: los endpoints públicos (login, register, mfa, swagger,
 * actuator/health) atraviesan; los protegidos caen en 401 vía Spring Security.
 *
 * <p>Nota D18: sin check de blacklist. La revocación de tokens vendrá con
 * {@code HU-F0X-token-rotation-logout} (mini-HU post-MVP).
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtService jwtService, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }
        String token = header.substring(PREFIX.length()).trim();
        try {
            Claims claims = jwtService.validate(token);
            AuthenticatedUser user =
                    new AuthenticatedUser(
                            UUID.fromString(claims.getSubject()),
                            claims.get("role", String.class));
            var authority = new SimpleGrantedAuthority("ROLE_" + user.role());
            var auth =
                    new UsernamePasswordAuthenticationToken(user, null, List.of(authority));
            SecurityContextHolder.getContext().setAuthentication(auth);
            filterChain.doFilter(request, response);
        } catch (TokenExpiredException e) {
            SecurityContextHolder.clearContext();
            writeError(request, response, "TOKEN_EXPIRED");
        } catch (TokenInvalidException e) {
            SecurityContextHolder.clearContext();
            writeError(request, response, "TOKEN_INVALID");
        }
    }

    private void writeError(HttpServletRequest request, HttpServletResponse response, String code)
            throws IOException {
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        ErrorResponse body =
                ErrorResponse.of(
                        401,
                        code,
                        ValidationMessages.humanFor(code),
                        request.getRequestURI(),
                        TraceIdFilter.currentTraceId());
        objectMapper.writeValue(response.getWriter(), body);
    }
}
