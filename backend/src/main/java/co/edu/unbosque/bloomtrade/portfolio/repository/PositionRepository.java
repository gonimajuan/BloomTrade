package co.edu.unbosque.bloomtrade.portfolio.repository;

import co.edu.unbosque.bloomtrade.portfolio.domain.Position;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Acceso a {@code app.positions}.
 *
 * <ul>
 *   <li>{@link #findByUserIdAndTicker} — decision point para upsert: si existe, incrementar;
 *       si no, insertar nueva.</li>
 *   <li>{@link #findByUserIdAndTickerForUpdate} — HU-F10: lock pessimistic para serializar
 *       ventas concurrentes sobre el mismo ticker. Convención D12: ambas ramas BUY y SELL
 *       deben adquirir locks en el mismo orden (primero {@code user_balances}, después
 *       {@code positions}) cuando necesiten ambos.</li>
 *   <li>{@link #deleteByUserIdAndTicker} — HU-F10 D1: cuando la venta liquida la posición
 *       completa (qty resultante = 0) se borra la fila en lugar de mantenerla con qty=0.</li>
 *   <li>{@link #findByUserId} — prep para HU-F16 Consultar portafolio (Día 8).</li>
 * </ul>
 */
public interface PositionRepository extends JpaRepository<Position, UUID> {

    Optional<Position> findByUserIdAndTicker(UUID userId, String ticker);

    /**
     * Lookup con lock pessimistic (SELECT ... FOR UPDATE) para serializar mutaciones
     * concurrentes sobre la misma posición. HU-F10 D12: necesario para invariante
     * {@code quantity >= 0} bajo concurrencia (dos ventas simultáneas del mismo ticker).
     *
     * <p>Debe invocarse dentro de una transacción JPA activa. Fuera de transacción
     * Spring lanza {@code TransactionRequiredException}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Position p WHERE p.userId = :userId AND p.ticker = :ticker")
    Optional<Position> findByUserIdAndTickerForUpdate(
            @Param("userId") UUID userId, @Param("ticker") String ticker);

    /**
     * DELETE de la fila {@code (userId, ticker)} de {@code app.positions}. Retorna el número
     * de filas afectadas (0 si la fila ya fue borrada por una transacción concurrente).
     * HU-F10 D1: invocada cuando la venta deja la posición en {@code quantity = 0}.
     */
    @Modifying
    @Query("DELETE FROM Position p WHERE p.userId = :userId AND p.ticker = :ticker")
    int deleteByUserIdAndTicker(@Param("userId") UUID userId, @Param("ticker") String ticker);

    List<Position> findByUserId(UUID userId);

    /**
     * HU-F16 D12 — filtro defensivo {@code quantity > 0}. HU-F10 D1 borra la fila al llegar a
     * {@code quantity = 0}, pero si por un bug futuro o un INSERT manual sobreviviera una fila
     * en ese estado, el listado del portafolio NO debe incluirla (sería confuso mostrar "0
     * AAPL"). El método {@link #findByUserId} se preserva sin filtro para los usos internos
     * que ya existen (test legacy).
     *
     * <p>D18 emergente Lote C: orden alfabético estable por ticker — necesario para tests
     * deterministas (JsonPath con filtros no se evalúa consistente en MockMvc) y buena UX
     * default (el frontend puede re-sortear si quiere).
     */
    List<Position> findByUserIdAndQuantityGreaterThanOrderByTicker(UUID userId, Integer minQuantity);
}
