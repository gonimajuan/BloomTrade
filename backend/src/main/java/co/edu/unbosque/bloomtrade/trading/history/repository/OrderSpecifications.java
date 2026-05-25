package co.edu.unbosque.bloomtrade.trading.history.repository;

import co.edu.unbosque.bloomtrade.trading.domain.Order;
import co.edu.unbosque.bloomtrade.trading.domain.OrderSide;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Factory estática de {@link Specification} para filtros dinámicos en el historial de
 * órdenes (HU-F17 plan D10 — Specification API sobre derived methods múltiples).
 *
 * <p>Compone vía {@code Specification.where(byUser(uid)).and(byTicker(t)).and(bySide(s))}.
 * Cada predicado es null-safe a nivel del caller (el caller decide si agrega o no según
 * los {@code Optional} de los query params).
 */
public final class OrderSpecifications {

    private OrderSpecifications() {}

    /** Filtro estricto por {@code user_id} — siempre presente para aislamiento cross-user. */
    public static Specification<Order> byUser(UUID userId) {
        return (root, query, cb) -> cb.equal(root.get("userId"), userId);
    }

    /** Filtro opcional por {@code ticker} exacto (case-sensitive). */
    public static Specification<Order> byTicker(String ticker) {
        return (root, query, cb) -> cb.equal(root.get("ticker"), ticker);
    }

    /** Filtro opcional por {@code side} (BUY o SELL). */
    public static Specification<Order> bySide(OrderSide side) {
        return (root, query, cb) -> cb.equal(root.get("side"), side);
    }
}
