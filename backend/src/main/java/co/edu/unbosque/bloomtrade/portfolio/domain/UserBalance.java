package co.edu.unbosque.bloomtrade.portfolio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Saldo del inversionista (tabla {@code app.user_balances}, migración V2). PK = FK a
 * {@code app.users(id)}. Monto en {@link BigDecimal} {@code NUMERIC(19,2)} (STACK.md §4.2,
 * CLAUDE.md #9 — nunca {@code double}).
 */
@Entity
@Table(schema = "app", name = "user_balances")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserBalance {

    /** Saldo inicial demo del inversionista nuevo (spec HU-F01 §5.1 paso 11). */
    public static final BigDecimal INITIAL_BALANCE = new BigDecimal("10000.00");

    public static final String DEFAULT_CURRENCY = "USD";

    @Id
    @Column(name = "user_id", updatable = false, nullable = false)
    private UUID userId;

    @Column(name = "balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private UserBalance(UUID userId, BigDecimal balance, String currency) {
        this.userId = userId;
        this.balance = balance;
        this.currency = currency;
    }

    /** Balance inicial USD 10,000.00 para un usuario recién registrado. */
    public static UserBalance initial(UUID userId) {
        return new UserBalance(userId, INITIAL_BALANCE, DEFAULT_CURRENCY);
    }
}
