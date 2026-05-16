package co.edu.unbosque.bloomtrade.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// Skeleton de Día 0. La validación real del token y el seteo de SecurityContext
// se implementan en HU-F02 (Día 2). Por ahora el filtro deja pasar cualquier request
// — los endpoints públicos los autoriza SecurityConfig; los demás caerán como 401
// porque ningún Authentication queda colocado en el contexto.
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        // TODO HU-F02: extraer Bearer token, validar firma+exp con JwtService,
        // cargar UserDetails, setear UsernamePasswordAuthenticationToken en el contexto.
        filterChain.doFilter(request, response);
    }
}
