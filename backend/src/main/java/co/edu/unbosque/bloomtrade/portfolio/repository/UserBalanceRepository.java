package co.edu.unbosque.bloomtrade.portfolio.repository;

import co.edu.unbosque.bloomtrade.portfolio.domain.UserBalance;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Acceso a {@code app.user_balances}. */
public interface UserBalanceRepository extends JpaRepository<UserBalance, UUID> {

    /**
     * Lookup con lock pessimistic (SELECT ... FOR UPDATE) para serializar débitos
     * concurrentes sobre el mismo balance. HU-F09 D13: necesario para invariante
     * "saldo no-negativo" bajo concurrencia (doble-click, pestañas paralelas).
     *
     * <p>Debe invocarse dentro de una transacción JPA activa. Fuera de transacción
     * Spring lanza {@code TransactionRequiredException}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM UserBalance b WHERE b.userId = :userId")
    Optional<UserBalance> findByUserIdForUpdate(@Param("userId") UUID userId);

    /**
     * Projection-only lookup del balance — NO carga el entity al L1 cache. Importante para
     * pre-checks dentro de la MISMA transacción que después hará {@link #findByUserIdForUpdate}:
     * si el entity está en cache, Hibernate puede devolverlo SIN emitir SELECT FOR UPDATE real,
     * y se rompe la serialización (concurrency bug observado en HU-F09 Lote G).
     */
    @Query("SELECT b.balance FROM UserBalance b WHERE b.userId = :userId")
    Optional<BigDecimal> findBalanceProjectionByUserId(@Param("userId") UUID userId);
}
