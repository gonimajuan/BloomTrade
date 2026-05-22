package co.edu.unbosque.bloomtrade.auth.subscription.repository;

import co.edu.unbosque.bloomtrade.auth.subscription.domain.StripeWebhookEvent;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acceso a {@code app.stripe_webhook_events} — tabla de idempotencia (HU-F06 §5.3.5). */
public interface StripeWebhookEventRepository extends JpaRepository<StripeWebhookEvent, UUID> {

    /**
     * Pre-check de idempotencia (opcional — el UNIQUE constraint también atrapa el caso). Útil
     * cuando se quiere evitar el SQL exception por motivo de logging cleaner; en runtime real el
     * INSERT con catch {@code DataIntegrityViolationException} es la fuente autoritativa.
     */
    boolean existsByStripeEventId(String stripeEventId);

    /** Lookup para inspección manual (Kibana / debugging). */
    Optional<StripeWebhookEvent> findByStripeEventId(String stripeEventId);
}
