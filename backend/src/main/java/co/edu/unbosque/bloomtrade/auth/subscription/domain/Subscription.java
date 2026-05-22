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
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Suscripción premium del usuario (tabla {@code app.subscriptions}, migración V4).
 *
 * <p>Una fila por intento de suscripción del usuario. Múltiples filas históricas permitidas,
 * pero solo una con {@link SubscriptionStatus#ACTIVE} por usuario (enforced por índice único
 * parcial {@code uq_one_active_subscription_per_user}).
 *
 * <p>Las mutaciones se hacen exclusivamente vía métodos de dominio (DDD, mismo patrón que
 * {@link co.edu.unbosque.bloomtrade.auth.domain.User#applyProfileUpdate} — D19 HU-F04). No se
 * exponen setters: cualquier consumidor que necesite mutar pasa por {@code markAs*} o
 * {@code scheduleCancellation/reactivate}.
 */
@Entity
@Table(schema = "app", name = "subscriptions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "stripe_customer_id", nullable = false, length = 50, updatable = false)
    private String stripeCustomerId;

    @Column(name = "stripe_subscription_id", nullable = false, length = 50, updatable = false)
    private String stripeSubscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan", nullable = false, length = 15, updatable = false)
    private BillingPlan plan;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SubscriptionStatus status;

    @Column(name = "current_period_start", nullable = false)
    private Instant currentPeriodStart;

    @Column(name = "current_period_end", nullable = false)
    private Instant currentPeriodEnd;

    @Column(name = "cancel_at_period_end", nullable = false)
    private boolean cancelAtPeriodEnd;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private Subscription(
            UUID userId,
            String stripeCustomerId,
            String stripeSubscriptionId,
            BillingPlan plan,
            Instant currentPeriodStart,
            Instant currentPeriodEnd) {
        this.userId = userId;
        this.stripeCustomerId = stripeCustomerId;
        this.stripeSubscriptionId = stripeSubscriptionId;
        this.plan = plan;
        this.status = SubscriptionStatus.ACTIVE;
        this.currentPeriodStart = currentPeriodStart;
        this.currentPeriodEnd = currentPeriodEnd;
        this.cancelAtPeriodEnd = false;
    }

    /** Crea una suscripción nueva en estado {@link SubscriptionStatus#ACTIVE}. */
    public static Subscription activate(
            UUID userId,
            String stripeCustomerId,
            String stripeSubscriptionId,
            BillingPlan plan,
            Instant currentPeriodStart,
            Instant currentPeriodEnd) {
        return new Subscription(
                userId,
                stripeCustomerId,
                stripeSubscriptionId,
                plan,
                currentPeriodStart,
                currentPeriodEnd);
    }

    /**
     * Marca la cancelación programada al fin de período (proviene del Customer Portal vía
     * webhook {@code customer.subscription.updated} con {@code cancel_at_period_end=true}).
     * El status permanece {@link SubscriptionStatus#ACTIVE} hasta que llegue
     * {@code current_period_end}.
     */
    public void scheduleCancellation(Instant newPeriodEnd) {
        this.cancelAtPeriodEnd = true;
        if (newPeriodEnd != null) {
            this.currentPeriodEnd = newPeriodEnd;
        }
    }

    /** Reactiva tras cancelación programada (Customer Portal — webhook v1.2 §5.2.2). */
    public void reactivate() {
        this.cancelAtPeriodEnd = false;
    }

    /** Estado terminal: la suscripción terminó (post {@code current_period_end} o forzada). */
    public void markAsCancelled() {
        this.status = SubscriptionStatus.CANCELLED;
    }

    /** Estado terminal: el pago de renovación falló. Downgrade inmediato (D10 — sin grace period). */
    public void markAsPastDue() {
        this.status = SubscriptionStatus.PAST_DUE;
    }

    /** Sincroniza el período (lo manda el webhook {@code customer.subscription.updated}). */
    public void syncPeriod(Instant newPeriodStart, Instant newPeriodEnd) {
        if (newPeriodStart != null) {
            this.currentPeriodStart = newPeriodStart;
        }
        if (newPeriodEnd != null) {
            this.currentPeriodEnd = newPeriodEnd;
        }
    }
}
