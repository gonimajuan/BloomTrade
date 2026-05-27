package co.edu.unbosque.bloomtrade.trading.service;

import co.edu.unbosque.bloomtrade.integration.alpaca.AlpacaApiException;
import co.edu.unbosque.bloomtrade.integration.alpaca.AlpacaTradingAdapter;
import co.edu.unbosque.bloomtrade.integration.alpaca.dto.AlpacaOrderResponse;
import co.edu.unbosque.bloomtrade.portfolio.service.PortfolioService;
import co.edu.unbosque.bloomtrade.trading.domain.Order;
import co.edu.unbosque.bloomtrade.trading.domain.OrderSide;
import co.edu.unbosque.bloomtrade.trading.domain.OrderStatus;
import co.edu.unbosque.bloomtrade.trading.event.OrderCanceledEvent;
import co.edu.unbosque.bloomtrade.trading.event.OrderExpiredEvent;
import co.edu.unbosque.bloomtrade.trading.event.OrderRejectedEvent;
import co.edu.unbosque.bloomtrade.trading.repository.OrderRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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
 * <p>Estados de Alpaca tratados en v2 (HU-F15):
 * <ul>
 *   <li>{@code filled}: materializa EXECUTED + side-aware mutation (heredado v1).</li>
 *   <li>{@code canceled}: materializa CANCELED + refund (BUY) / restore (SELL). Distingue por
 *       {@code cancel_requested_at} del order: si seteado → user request polling-timeout (D6);
 *       si null → broker cancel outbound (TIF day, etc.).</li>
 *   <li>{@code expired}: materializa EXPIRED + refund/restore (D-EMAIL-EXPIRED-REUSE D15).</li>
 *   <li>{@code rejected}: materializa REJECTED + refund/restore. NO emite email-rejected (los
 *       templates F09/F10 asumen "se intentó pero falló al momento del placeOrder"; un rejected
 *       reportado horas después sería confuso). Solo audit log.</li>
 *   <li>{@code partially_filled}: D19 D-RECONCILE-LAZY-V2-SCOPE — fuera de scope F15. Log
 *       ERROR + sigue PENDING. Si emerge en testing real, registrar como deuda y completar.</li>
 *   <li>otros estados no-terminales ({@code accepted}, {@code new}, etc.): sigue PENDING.</li>
 * </ul>
 *
 * <p>Eventos: v1 NO publicaba {@code OrderExecutedEvent} para evitar emails retroactivos
 * confusos. v2 SÍ publica {@code OrderCanceledEvent} / {@code OrderExpiredEvent} porque la
 * transición fue solicitada (por user o broker) y el email confirma al usuario. Para
 * {@code OrderRejectedEvent} de transiciones reconcile lazy: NO se publica (audit-only).
 */
@Service
public class OrderReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(OrderReconciliationService.class);

    private final OrderRepository orderRepository;
    private final AlpacaTradingAdapter alpacaTradingAdapter;
    private final PortfolioService portfolioService;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    public OrderReconciliationService(
            OrderRepository orderRepository,
            AlpacaTradingAdapter alpacaTradingAdapter,
            PortfolioService portfolioService,
            ApplicationEventPublisher eventPublisher,
            PlatformTransactionManager transactionManager) {
        this.orderRepository = orderRepository;
        this.alpacaTradingAdapter = alpacaTradingAdapter;
        this.portfolioService = portfolioService;
        this.eventPublisher = eventPublisher;
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
     * Devuelve true si la orden fue materializada (transicionó a estado terminal).
     *
     * <p>v2: dispatch por status retornado por Alpaca. Se valida el estado actual de la orden
     * al inicio (defensivo contra race conditions entre el listado de PENDING y el procesamiento).
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

        if (alpacaResp.isFilled()) {
            return applyFilledTransition(order, alpacaResp);
        }
        if (alpacaResp.isCanceled()) {
            return applyCanceledTransition(order, alpacaResp);
        }
        if (alpacaResp.isExpired()) {
            return applyExpiredTransition(order, alpacaResp);
        }
        if (alpacaResp.isRejected()) {
            return applyRejectedTransition(order, alpacaResp);
        }
        if (alpacaResp.isPartiallyFilled()) {
            // D19 D-RECONCILE-LAZY-V2-SCOPE: fuera de scope F15. Log ERROR para visibilidad
            // y dejamos la orden en PENDING. Si emerge en testing real, registrar como deuda
            // post-F15.
            log.error(
                    "Reconcile lazy v2: partially_filled detectado para orderId={} alpacaId={} — fuera de scope F15 (D19)",
                    order.getId(),
                    order.getAlpacaOrderId());
            return false;
        }
        // accepted, new, pending_cancel, etc. — sigue PENDING. No-op.
        return false;
    }

    /** Heredado v1 — materializa fill. NO publica OrderExecutedEvent (email retroactivo confuso). */
    private boolean applyFilledTransition(Order order, AlpacaOrderResponse alpacaResp) {
        BigDecimal execPrice = alpacaResp.filledAvgPrice();
        if (execPrice == null || execPrice.signum() <= 0) {
            log.warn(
                    "Reconcile lazy: orderId={} filled sin filledAvgPrice válido — omitido",
                    order.getId());
            return false;
        }

        order.markAsExecuted(alpacaResp.id(), execPrice);

        if (order.getSide() == OrderSide.BUY) {
            portfolioService.upsertPosition(
                    order.getUserId(), order.getTicker(), order.getQuantity(), execPrice);
        } else {
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

    /**
     * Convenience overload — deriva {@code source} desde el campo {@code cancel_requested_at}
     * del order: si seteado → {@link OrderCanceledEvent.CancelSource#USER_REQUEST} (polling-timeout
     * que se materializa); si null → {@link OrderCanceledEvent.CancelSource#BROKER_CANCEL}
     * (cancel outbound del broker).
     *
     * <p>Para forzar {@link OrderCanceledEvent.CancelSource#DRIFT_RECONCILE} (drift detected
     * desde {@code TradingService.cancelOrder}), usar la sobrecarga con {@code source} explícito.
     */
    boolean applyCanceledTransition(Order order, AlpacaOrderResponse alpacaResp) {
        OrderCanceledEvent.CancelSource source =
                order.getCancelRequestedAt() != null
                        ? OrderCanceledEvent.CancelSource.USER_REQUEST
                        : OrderCanceledEvent.CancelSource.BROKER_CANCEL;
        return applyCanceledTransition(order, alpacaResp, source);
    }

    /**
     * HU-F15 — materializa transición CANCELED detectada via reconcile lazy v2 (outbound del
     * broker o materialización de polling-timeout). Refund (BUY) o restore (SELL) + mark
     * status + publish event para que {@code OrderEventListener.onOrderCanceled} envíe email.
     *
     * <p>Package-private para reuso desde {@code TradingService.cancelOrder} drift path
     * (Alpaca DELETE 404/422 → reconcile inline con source=DRIFT_RECONCILE).
     */
    boolean applyCanceledTransition(
            Order order, AlpacaOrderResponse alpacaResp, OrderCanceledEvent.CancelSource source) {
        BigDecimal refundedAmount = null;
        Integer restoredQty = null;

        if (order.getSide() == OrderSide.BUY) {
            refundedAmount = order.getQuotedTotal();
            portfolioService.credit(order.getUserId(), refundedAmount);
        } else {
            restoredQty = order.getQuantity();
            BigDecimal avgBuyPrice = order.getAvgBuyPriceAtSubmission();
            if (avgBuyPrice == null) {
                avgBuyPrice = order.getQuotedUnitPrice();
                log.warn(
                        "Reconcile lazy v2: SELL legacy sin avg_buy_price_at_submission — fallback quoted_unit_price para orderId={}",
                        order.getId());
            }
            portfolioService.upsertPosition(
                    order.getUserId(), order.getTicker(), restoredQty, avgBuyPrice);
        }

        order.markAsCanceled();
        orderRepository.save(order);

        eventPublisher.publishEvent(
                new OrderCanceledEvent(
                        order.getId(),
                        order.getUserId(),
                        order.getTicker(),
                        order.getSide(),
                        order.getQuantity(),
                        order.getAlpacaOrderId(),
                        order.getQuotedTotal(),
                        refundedAmount,
                        restoredQty,
                        order.getCanceledAt(),
                        source));

        log.info(
                "Reconcile lazy v2: orderId={} side={} ticker={} → CANCELED (source={})",
                order.getId(),
                order.getSide(),
                order.getTicker(),
                source);
        return true;
    }

    /**
     * HU-F15 — materializa transición EXPIRED (TIF day expirado en Alpaca sin fill). Idéntico
     * reverse que CANCELED. Publish {@link OrderExpiredEvent}; el listener reusa los templates
     * {@code order-canceled-*} con flag {@code isExpired=true} (D15 D-EMAIL-EXPIRED-REUSE).
     */
    boolean applyExpiredTransition(Order order, AlpacaOrderResponse alpacaResp) {
        BigDecimal refundedAmount = null;
        Integer restoredQty = null;

        if (order.getSide() == OrderSide.BUY) {
            refundedAmount = order.getQuotedTotal();
            portfolioService.credit(order.getUserId(), refundedAmount);
        } else {
            restoredQty = order.getQuantity();
            BigDecimal avgBuyPrice = order.getAvgBuyPriceAtSubmission();
            if (avgBuyPrice == null) {
                avgBuyPrice = order.getQuotedUnitPrice();
            }
            portfolioService.upsertPosition(
                    order.getUserId(), order.getTicker(), restoredQty, avgBuyPrice);
        }

        order.markAsExpired();
        orderRepository.save(order);

        eventPublisher.publishEvent(
                new OrderExpiredEvent(
                        order.getId(),
                        order.getUserId(),
                        order.getTicker(),
                        order.getSide(),
                        order.getQuantity(),
                        order.getAlpacaOrderId(),
                        order.getQuotedTotal(),
                        refundedAmount,
                        restoredQty,
                        order.getExpiredAt()));

        log.info(
                "Reconcile lazy v2: orderId={} side={} ticker={} → EXPIRED",
                order.getId(),
                order.getSide(),
                order.getTicker());
        return true;
    }

    /**
     * HU-F15 — materializa transición REJECTED post-encolado (Alpaca rechaza una orden que
     * previamente había aceptado, raro pero documentado: "compliance review", "market closed
     * unexpectedly", etc.). Reverse análogo a CANCELED/EXPIRED.
     *
     * <p>NO publica {@code OrderRejectedEvent} para evitar email retroactivo confuso (los
     * templates F09/F10 asumen rechazo al momento del submit, no horas después). Solo log INFO
     * — el audit trail vive en el rastro de cambios persistidos a BD.
     */
    boolean applyRejectedTransition(Order order, AlpacaOrderResponse alpacaResp) {
        BigDecimal refundedAmount = null;
        Integer restoredQty = null;

        if (order.getSide() == OrderSide.BUY) {
            refundedAmount = order.getQuotedTotal();
            portfolioService.credit(order.getUserId(), refundedAmount);
        } else {
            restoredQty = order.getQuantity();
            BigDecimal avgBuyPrice = order.getAvgBuyPriceAtSubmission();
            if (avgBuyPrice == null) {
                avgBuyPrice = order.getQuotedUnitPrice();
            }
            portfolioService.upsertPosition(
                    order.getUserId(), order.getTicker(), restoredQty, avgBuyPrice);
        }

        String reason =
                alpacaResp.rejectedReason() != null
                        ? alpacaResp.rejectedReason()
                        : "POST_QUEUED_REJECTED";
        order.markAsRejected("ALPACA_POST_QUEUED_REJECTED", reason);
        orderRepository.save(order);

        log.info(
                "Reconcile lazy v2: orderId={} side={} ticker={} → REJECTED post-queued (reason={})",
                order.getId(),
                order.getSide(),
                order.getTicker(),
                reason);
        return true;
    }

    /**
     * HU-F15 — drift reconcile inline para {@code TradingService.cancelOrder} cuando Alpaca
     * responde 404/422 al DELETE (drift entre BD local y Alpaca). Fetch del estado real +
     * dispatch al {@code applyXxxTransition} correspondiente. Source=DRIFT_RECONCILE para
     * cancel transitions, distinguiéndolas de las generadas por user request o broker outbound.
     *
     * <p>NO captura excepciones del getOrder — si Alpaca está caído también para el GET, el
     * caller {@code TradingService} las trata como BROKER_UNAVAILABLE.
     *
     * @return el Order actualizado tras materializar la transición; lanza
     *     {@link IllegalStateException} si el estado real es no-terminal (caso raro, drift
     *     transitorio que se resolverá).
     */
    public Order applyDriftReconcile(Order order) {
        AlpacaOrderResponse realState = alpacaTradingAdapter.getOrder(order.getAlpacaOrderId());
        log.warn(
                "Drift reconcile inline para orderId={} alpacaId={} — Alpaca dice status={}",
                order.getId(),
                order.getAlpacaOrderId(),
                realState.status());

        boolean materialized;
        if (realState.isCanceled()) {
            materialized =
                    applyCanceledTransition(
                            order, realState, OrderCanceledEvent.CancelSource.DRIFT_RECONCILE);
        } else if (realState.isFilled()) {
            materialized = applyFilledTransition(order, realState);
        } else if (realState.isExpired()) {
            materialized = applyExpiredTransition(order, realState);
        } else if (realState.isRejected()) {
            materialized = applyRejectedTransition(order, realState);
        } else {
            throw new IllegalStateException(
                    "Drift reconcile: estado Alpaca no-terminal "
                            + realState.status()
                            + " para orderId="
                            + order.getId());
        }
        if (!materialized) {
            throw new IllegalStateException(
                    "Drift reconcile: no se pudo materializar orderId=" + order.getId());
        }
        return orderRepository
                .findById(order.getId())
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Drift reconcile: order desapareció tras materialize: "
                                                + order.getId()));
    }
}
