package co.edu.unbosque.bloomtrade.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import net.logstash.logback.marker.Markers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Implementación de {@link Auditor} (componente {@code AuditLogger} de ARCHITECTURE.md §4).
 *
 * <p>Escribe el evento como una línea JSON estructurada vía {@code logstash-logback-encoder}: cada
 * campo de ARCHITECTURE.md §12 se emite como atributo de primer nivel. El flag {@code audit=true}
 * permite que el pipeline Logstash rutee estos eventos al índice {@code audit-events-{YYYY.MM}}
 * (STACK.md §6.4) separándolos de los logs operacionales.
 */
@Component
public class AuditLogger implements Auditor {

    private static final Logger log = LoggerFactory.getLogger(AuditLogger.class);

    @Override
    public void record(AuditEvent event) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("audit", true);
        fields.put("timestamp", event.timestamp().toString());
        fields.put("event_type", event.eventType().name());
        fields.put("actor_id", event.actorId());
        fields.put("actor_role", event.actorRole());
        fields.put("resource", event.resource());
        fields.put("result", event.result().name());
        fields.put("ip_origin", event.ipOrigin());
        fields.put("session_id", event.sessionId());
        fields.put("order_id", event.orderId());
        fields.put("details", event.details());

        log.info(Markers.appendEntries(fields), "audit_event {}", event.eventType());
    }
}
