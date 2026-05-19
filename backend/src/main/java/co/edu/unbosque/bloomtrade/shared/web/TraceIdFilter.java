package co.edu.unbosque.bloomtrade.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Asigna un {@code traceId} (UUID) a cada request, lo expone en el MDC y en el header de respuesta
 * {@code X-Trace-Id} (STACK.md §9.3). Reutilizable por todo el proyecto.
 *
 * <p>El {@code logstash-logback-encoder} incluye el MDC automáticamente, por lo que tanto los logs
 * de aplicación como los eventos de auditoría heredan el mismo {@code traceId}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** Clave del traceId en el MDC y nombre del header de respuesta. */
    public static final String TRACE_ID = "traceId";

    private static final String HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = UUID.randomUUID().toString();
        MDC.put(TRACE_ID, traceId);
        response.setHeader(HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID);
        }
    }

    /** traceId del request en curso; genera uno nuevo si el filtro aún no lo fijó. */
    public static String currentTraceId() {
        String traceId = MDC.get(TRACE_ID);
        return traceId != null ? traceId : UUID.randomUUID().toString();
    }
}
