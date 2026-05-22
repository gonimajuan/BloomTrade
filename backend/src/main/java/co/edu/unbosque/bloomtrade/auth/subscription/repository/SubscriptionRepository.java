package co.edu.unbosque.bloomtrade.auth.subscription.repository;

import co.edu.unbosque.bloomtrade.auth.subscription.domain.Subscription;
import co.edu.unbosque.bloomtrade.auth.subscription.domain.SubscriptionStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acceso a {@code app.subscriptions} (HU-F06). */
public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    /**
     * Existencia de una suscripción ACTIVE del usuario. Consumido por {@code isPremium} (G5
     * extensión de {@code GET /me} con {@code isPremium}) y por la validación pre-checkout
     * (spec §5.3.1 — 409 si ya hay una activa).
     */
    boolean existsByUserIdAndStatus(UUID userId, SubscriptionStatus status);

    /** Devuelve la suscripción ACTIVE del usuario si existe. */
    Optional<Subscription> findByUserIdAndStatus(UUID userId, SubscriptionStatus status);

    /**
     * Devuelve la fila más reciente del usuario (cualquier status). Útil para {@code GET
     * /subscriptions/me} (SPEC §6.1.2): si está en estado terminal (CANCELLED/PAST_DUE) la UI
     * la muestra como histórico mientras ofrece re-suscribirse.
     */
    Optional<Subscription> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);

    /** Lookup por id de Stripe — usado en handlers de webhook. */
    Optional<Subscription> findByStripeSubscriptionId(String stripeSubscriptionId);
}
