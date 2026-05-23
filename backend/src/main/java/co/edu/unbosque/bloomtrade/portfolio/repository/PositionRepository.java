package co.edu.unbosque.bloomtrade.portfolio.repository;

import co.edu.unbosque.bloomtrade.portfolio.domain.Position;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Acceso a {@code app.positions}.
 *
 * <ul>
 *   <li>{@link #findByUserIdAndTicker} — decision point para upsert: si existe, incrementar;
 *       si no, insertar nueva.</li>
 *   <li>{@link #findByUserId} — prep para HU-F16 Consultar portafolio (Día 8).</li>
 * </ul>
 */
public interface PositionRepository extends JpaRepository<Position, UUID> {

    Optional<Position> findByUserIdAndTicker(UUID userId, String ticker);

    List<Position> findByUserId(UUID userId);
}
