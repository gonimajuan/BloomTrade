package co.edu.unbosque.bloomtrade.admin.repository;

import co.edu.unbosque.bloomtrade.admin.domain.CommissionRate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Acceso a {@code config.commission_rates}.
 *
 * <p>{@link #findFirstByRoleAndValidToIsNullOrderByValidFromDesc} devuelve la fila activa
 * (la que NO tiene {@code valid_to} setteado) para un rol dado. UNIQUE PARTIAL constraint
 * garantiza que como máximo hay UNA. Si no hay ninguna (BD vacía), el caller
 * ({@code ConfigurationManager}) debe usar el fallback de {@code trading.default-commission-pct}.
 */
public interface CommissionRateRepository extends JpaRepository<CommissionRate, UUID> {

    Optional<CommissionRate> findFirstByRoleAndValidToIsNullOrderByValidFromDesc(String role);
}
