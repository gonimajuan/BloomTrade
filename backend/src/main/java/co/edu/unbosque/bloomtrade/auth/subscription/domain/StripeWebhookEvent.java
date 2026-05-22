package co.edu.unbosque.bloomtrade.auth.subscription.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Registro de idempotencia para webhooks de Stripe (tabla {@code app.stripe_webhook_events},
 * migración V4 — spec HU-F06 §5.3.5).
 *
 * <p>El UNIQUE sobre {@code stripe_event_id} es el corazón del mecanismo: cuando Stripe reintenta
 * el mismo evento, el segundo INSERT viola el constraint y se captura como DUPLICATE en lugar
 * de reprocesar el negocio.
 */
@Entity
@Table(schema = "app", name = "stripe_webhook_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StripeWebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "stripe_event_id", nullable = false, length = 80, updatable = false)
    private String stripeEventId;

    @Column(name = "event_type", nullable = false, length = 80, updatable = false)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WebhookEventStatus status;

    @CreationTimestamp
    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb", updatable = false)
    private String payload;

    private StripeWebhookEvent(String stripeEventId, String eventType, String payload) {
        this.stripeEventId = stripeEventId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = WebhookEventStatus.RECEIVED;
    }

    /** Crea un registro nuevo en estado RECEIVED, listo para que el handler procese el negocio. */
    public static StripeWebhookEvent received(String stripeEventId, String eventType, String payload) {
        return new StripeWebhookEvent(stripeEventId, eventType, payload);
    }

    public void markAsProcessed() {
        this.status = WebhookEventStatus.PROCESSED;
        this.processedAt = Instant.now();
    }

    public void markAsFailed(String error) {
        this.status = WebhookEventStatus.FAILED;
        this.errorMessage = error;
        this.processedAt = Instant.now();
    }

    public void markAsOrphan() {
        this.status = WebhookEventStatus.ORPHAN;
        this.processedAt = Instant.now();
    }
}
