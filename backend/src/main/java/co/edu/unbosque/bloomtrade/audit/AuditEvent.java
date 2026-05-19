package co.edu.unbosque.bloomtrade.audit;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Evento de auditoría inmutable con la estructura exigida por ARCHITECTURE.md §12.
 *
 * <p>El constructor canónico valida los campos obligatorios <strong>antes</strong> de que el evento
 * pueda emitirse (STACK.md §6.3). Para flujos no autenticados (p.ej. registro) {@code actorId},
 * {@code actorRole}, {@code ipOrigin}, {@code sessionId} y {@code orderId} pueden ser {@code null}.
 * Builder propio (no Lombok) para máxima previsibilidad de compilación sobre {@code record}.
 */
public record AuditEvent(
        Instant timestamp,
        AuditEventType eventType,
        String actorId,
        String actorRole,
        String resource,
        AuditResult result,
        String ipOrigin,
        String sessionId,
        String orderId,
        Map<String, Object> details) {

    /** Resultado del intento auditado (ARCHITECTURE.md §12 — campo {@code result}). */
    public enum AuditResult {
        ALLOWED,
        DENIED
    }

    public AuditEvent {
        if (eventType == null) {
            throw new IllegalArgumentException("AuditEvent.eventType es obligatorio");
        }
        if (result == null) {
            throw new IllegalArgumentException("AuditEvent.result es obligatorio");
        }
        if (resource == null || resource.isBlank()) {
            throw new IllegalArgumentException("AuditEvent.resource es obligatorio");
        }
        timestamp = timestamp != null ? timestamp : Instant.now();
        details =
                details != null
                        ? Collections.unmodifiableMap(new LinkedHashMap<>(details))
                        : Map.of();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder fluido; {@code details} admite valores {@code null}. */
    public static final class Builder {
        private Instant timestamp;
        private AuditEventType eventType;
        private String actorId;
        private String actorRole;
        private String resource;
        private AuditResult result;
        private String ipOrigin;
        private String sessionId;
        private String orderId;
        private final Map<String, Object> details = new LinkedHashMap<>();

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder eventType(AuditEventType eventType) {
            this.eventType = eventType;
            return this;
        }

        public Builder actorId(String actorId) {
            this.actorId = actorId;
            return this;
        }

        public Builder actorRole(String actorRole) {
            this.actorRole = actorRole;
            return this;
        }

        public Builder resource(String resource) {
            this.resource = resource;
            return this;
        }

        public Builder result(AuditResult result) {
            this.result = result;
            return this;
        }

        public Builder ipOrigin(String ipOrigin) {
            this.ipOrigin = ipOrigin;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder orderId(String orderId) {
            this.orderId = orderId;
            return this;
        }

        public Builder detail(String key, Object value) {
            this.details.put(key, value);
            return this;
        }

        public AuditEvent build() {
            return new AuditEvent(
                    timestamp,
                    eventType,
                    actorId,
                    actorRole,
                    resource,
                    result,
                    ipOrigin,
                    sessionId,
                    orderId,
                    details);
        }
    }
}
