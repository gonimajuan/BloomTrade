package co.edu.unbosque.bloomtrade.trading.repository;

import co.edu.unbosque.bloomtrade.trading.domain.Order;
import co.edu.unbosque.bloomtrade.trading.domain.OrderStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Acceso a {@code app.orders}.
 *
 * <ul>
 *   <li>{@link #findByClientOrderId} — corazón de la idempotencia (HU-F09 D14): si la misma
 *       {@code client_order_id} llega dos veces, devolvemos la orden existente sin crear otra
 *       ni invocar a Alpaca.</li>
 *   <li>{@link #findByUserIdOrderBySubmittedAtDesc} — prep para HU-F17 (post-MVP, historial).</li>
 *   <li>{@link #findByUserIdAndStatusAndAlpacaOrderIdIsNotNullOrderBySubmittedAtDesc} —
 *       HU-F16 D4: alimenta la sección {@code pendingOrders[]} de
 *       {@code GET /api/v1/portfolio/positions}. Solo órdenes encoladas en Alpaca (status
 *       intermedio antes del submit a Alpaca tienen {@code alpaca_order_id IS NULL} y NO se
 *       exponen al cliente).</li>
 * </ul>
 */
public interface OrderRepository
        extends JpaRepository<Order, UUID>, JpaSpecificationExecutor<Order> {

    Optional<Order> findByClientOrderId(UUID clientOrderId);

    List<Order> findByUserIdOrderBySubmittedAtDesc(UUID userId);

    List<Order> findByUserIdAndStatusAndAlpacaOrderIdIsNotNullOrderBySubmittedAtDesc(
            UUID userId, OrderStatus status);

    // HU-F17 extiende JpaSpecificationExecutor para soporte de filtros dinámicos
    // (ticker, side) + paginación nativa Spring Data via OrderSpecifications.
}
