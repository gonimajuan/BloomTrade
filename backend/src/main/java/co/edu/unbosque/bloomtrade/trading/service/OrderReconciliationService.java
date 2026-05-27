package co.edu.unbosque.bloomtrade.trading.service;

import co.edu.unbosque.bloomtrade.integration.alpaca.AlpacaApiException;
import co.edu.unbosque.bloomtrade.integration.alpaca.AlpacaTradingAdapter;
import co.edu.unbosque.bloomtrade.integration.alpaca.dto.AlpacaOrderResponse;
import co.edu.unbosque.bloomtrade.portfolio.service.PortfolioService;
import co.edu.unbosque.bloomtrade.trading.domain.Order;
import co.edu.unbosque.bloomtrade.trading.domain.OrderSide;
import co.edu.unbosque.bloomtrade.trading.domain.OrderStatus;
import co.edu.unbosque.bloomtrade.trading.repository.OrderRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reconciliación lazy de órdenes encoladas en Alpaca (cierre funcional deuda viva #8 para
 * single-user MVP, sin job nocturno ni webhooks).
 *
 * <p>Modelo: en el path "no-terminal tras polling" del placeOrder (D29 F09 + D9 F10), la
 * orden queda como {@code PENDING + alpaca_order_id != null} esperando fill. Cada vez que
 * el frontend pide {@code /portfolio} o {@code /dashboard}, este servicio lista esas PENDING,
 * consulta el estado real en Alpaca y materializa los fills en BD: UPDATE order EXECUTED +
 * UPSERT position (BUY) o CREDIT balance (SELL).
 *
 * <p>Política de fallos: best-effort. Si Alpaca está caído al momento de reconciliar, se
 * loggea WARN y se devuelve el endpoint sin reconciliar (el usuario verá data ligeramente
 * stale para esa orden particular hasta el próximo refresh).
 *
 * <p>Estados de Alpaca tratados en v1:
 * <ul>
 *   <li>{@code filled}: materializa EXECUTED + side-aware mutation.</li>
 *   <li>otros estados no-terminales ({@code accepted}, {@code new}, etc.): sigue PENDING.</li>
 *   <li>terminales no-filled ({@code rejected}, {@code canceled}, {@code expired}): se omiten
 *       en v1 — reversal de balance/position queda como deuda v2.</li>
 * </ul>
 *
 * <p>No se emiten eventos {@code OrderExecutedEvent} desde acá: la orden tiene minutos/días
 * de antigüedad y un email "ejecutada" retroactivo sería confuso. La trazabilidad se mantiene
 * vía logs INFO de este servicio + cambios persistidos en BD.
 */
@Service
public class OrderReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(OrderReconciliationService.class);

    private final OrderRepository orderRepository;
    private final AlpacaTradingAdapter alpacaTradingAdapter;
    private final PortfolioService portfolioService;
    private final TransactionTemplate transactionTemplate;

    public OrderReconciliationService(
            OrderRepository orderRepository,
            AlpacaTradingAdapter alpacaTradingAdapter,
            PortfolioService portfolioService,
            PlatformTransactionManager transactionManager) {
        this.orderRepository = orderRepository;
        this.alpacaTradingAdapter = alpacaTradingAdapter;
        this.portfolioService = portfolioService;
        // REQUIRES_NEW: cada orden se procesa en su propia transacción para que un fallo
        // (excepción de Alpaca, conflicto de UPDATE, etc.) no haga rollback de las demás ni
        // afecte la transacción readOnly del endpoint que invocó este reconcile.
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        def.setName("reconcileOne");
        this.transactionTemplate = new TransactionTemplate(transactionManager, def);
    }

    /**
     * Reconcilia todas las órdenes {@code PENDING} del usuario contra Alpaca. Best-effort:
     * captura excepciones por orden y continúa con la siguiente — nunca propaga al caller.
     *
     * @return cantidad de órdenes que pasaron a EXECUTED en esta llamada.
     */
    public int reconcilePending(UUID userId) {
        List<Order> pending =
                orderRepository
                        .findByUserIdAndStatusAndAlpacaOrderIdIsNotNullOrderBySubmittedAtDesc(
                                userId, OrderStatus.PENDING);
        if (pending.isEmpty()) {
            return 0;
        }
        log.debug(
                "Reconcile lazy: userId={} pendingCount={}", userId, pending.size());

        int executed = 0;
        for (Order order : pending) {
            try {
                Boolean materialized =
                        transactionTemplate.execute(status -> reconcileOne(order.getId()));
                if (Boolean.TRUE.equals(materialized)) {
                    executed++;
                }
            } catch (Exception e) {
                log.warn(
                        "Reconcile lazy: orderId={} alpacaId={} omitido — {}",
                        order.getId(),
                        order.getAlpacaOrderId(),
                        e.getMessage());
            }
        }
        if (executed > 0) {
            log.info(
                    "Reconcile lazy: userId={} ejecutó {} de {} órdenes",
                    userId,
                    executed,
                    pending.size());
        }
        return executed;
    }

    /**
     * Procesa UNA orden en la transacción del {@link TransactionTemplate} abierto por el caller.
     * Devuelve true si la orden fue materializada como EXECUTED.
     *
     * <p>Se valida el estado actual de la orden al inicio (defensivo contra race conditions
     * entre el listado de PENDING y el procesamiento).
     */
    private boolean reconcileOne(UUID orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null
                || order.getStatus() != OrderStatus.PENDING
                || order.getAlpacaOrderId() == null) {
            return false;
        }

        AlpacaOrderResponse alpacaResp;
        try {
            alpacaResp = alpacaTradingAdapter.getOrder(order.getAlpacaOrderId());
        } catch (AlpacaApiException e) {
            log.warn(
                    "Reconcile lazy: Alpaca getOrder fallo orderId={} alpacaId={}: {}",
                    order.getId(),
                    order.getAlpacaOrderId(),
                    e.getMessage());
            return false;
        }

        if (!alpacaResp.isFilled()) {
            // Sigue accepted/new en Alpaca, o terminal-no-filled (rejected/canceled/expired —
            // reversal queda fuera de scope v1).
            return false;
        }

        BigDecimal execPrice = alpacaResp.filledAvgPrice();
        if (execPrice == null || execPrice.signum() <= 0) {
            log.warn(
                    "Reconcile lazy: orderId={} filled sin filledAvgPrice válido — omitido",
                    order.getId());
            return false;
        }

        order.markAsExecuted(alpacaResp.id(), execPrice);

        if (order.getSide() == OrderSide.BUY) {
            // BUY queued: balance ya descontado (quoted_total al place — D29 F09). Solo upsert
            // position con el precio real de fill (weighted avg si ya hay posición previa).
            portfolioService.upsertPosition(
                    order.getUserId(),
                    order.getTicker(),
                    order.getQuantity(),
                    execPrice);
        } else {
            // SELL queued: position ya decrementada optimistamente (D-SELL-QUEUED-RISK).
            // Falta acreditar el producto neto (quoted_total ya está calculado side-aware).
            portfolioService.credit(order.getUserId(), order.getQuotedTotal());
        }

        orderRepository.save(order);
        log.info(
                "Reconcile lazy: orderId={} side={} ticker={} qty={} filled@{} → EXECUTED",
                order.getId(),
                order.getSide(),
                order.getTicker(),
                order.getQuantity(),
                execPrice);
        return true;
    }
}
