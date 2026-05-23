package co.edu.unbosque.bloomtrade.trading.repository;

import co.edu.unbosque.bloomtrade.trading.domain.Order;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Acceso a {@code app.orders}.
 *
 * <ul>
 *   <li>{@link #findByClientOrderId} — corazón de la idempotencia (HU-F09 D14): si la misma
 *       {@code client_order_id} llega dos veces, devolvemos la orden existente sin crear otra
 *       ni invocar a Alpaca.</li>
 *   <li>{@link #findByUserIdOrderBySubmittedAtDesc} — prep para HU-F17 (post-MVP, historial).</li>
 * </ul>
 */
public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByClientOrderId(UUID clientOrderId);

    List<Order> findByUserIdOrderBySubmittedAtDesc(UUID userId);
}
