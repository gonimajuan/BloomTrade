package co.edu.unbosque.bloomtrade.trading.history.service;

import co.edu.unbosque.bloomtrade.trading.domain.Order;
import co.edu.unbosque.bloomtrade.trading.domain.OrderSide;
import co.edu.unbosque.bloomtrade.trading.history.repository.OrderSpecifications;
import co.edu.unbosque.bloomtrade.trading.repository.OrderRepository;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Servicio del historial paginado de órdenes (HU-F17 Lote C — plan D10).
 *
 * <p>Compone {@link Specification} dinámicamente según filtros opcionales:
 * {@code byUser} siempre (aislamiento cross-user obligatorio), {@code byTicker} y
 * {@code bySide} solo si el {@link Optional} está presente. Delega a
 * {@link OrderRepository#findAll(Specification, Pageable)} (auto-provided por
 * {@code JpaSpecificationExecutor}).
 *
 * <p>Plan D-AUDIT-EVENTS: NO emite audit events (read-only, consistente con F16+F21).
 */
@Service
public class OrderHistoryService {

    private static final Logger log = LoggerFactory.getLogger(OrderHistoryService.class);

    private final OrderRepository orderRepository;

    public OrderHistoryService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional(readOnly = true)
    public Page<Order> list(
            UUID userId,
            Optional<String> ticker,
            Optional<OrderSide> side,
            Pageable pageable) {
        Specification<Order> spec = Specification.where(OrderSpecifications.byUser(userId));
        if (ticker.isPresent()) {
            spec = spec.and(OrderSpecifications.byTicker(ticker.get()));
        }
        if (side.isPresent()) {
            spec = spec.and(OrderSpecifications.bySide(side.get()));
        }
        Page<Order> page = orderRepository.findAll(spec, pageable);
        log.debug(
                "OrderHistory.list userId={} ticker={} side={} pageNo={} returned={} totalElements={}",
                userId,
                ticker.orElse("*"),
                side.map(Enum::name).orElse("*"),
                pageable.getPageNumber(),
                page.getNumberOfElements(),
                page.getTotalElements());
        return page;
    }
}
