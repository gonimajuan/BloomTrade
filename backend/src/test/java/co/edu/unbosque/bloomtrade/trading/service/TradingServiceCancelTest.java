package co.edu.unbosque.bloomtrade.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.unbosque.bloomtrade.admin.service.CommissionManager;
import co.edu.unbosque.bloomtrade.admin.service.MarketScheduleManager;
import co.edu.unbosque.bloomtrade.audit.AuditEvent;
import co.edu.unbosque.bloomtrade.audit.AuditEventType;
import co.edu.unbosque.bloomtrade.audit.Auditor;
import co.edu.unbosque.bloomtrade.auth.repository.UserRepository;
import co.edu.unbosque.bloomtrade.integration.alpaca.AlpacaApiException;
import co.edu.unbosque.bloomtrade.integration.alpaca.AlpacaOrderNotFoundException;
import co.edu.unbosque.bloomtrade.integration.alpaca.AlpacaTradingAdapter;
import co.edu.unbosque.bloomtrade.integration.alpaca.MarketDataAdapter;
import co.edu.unbosque.bloomtrade.portfolio.domain.UserBalance;
import co.edu.unbosque.bloomtrade.portfolio.repository.UserBalanceRepository;
import co.edu.unbosque.bloomtrade.portfolio.service.PortfolioService;
import co.edu.unbosque.bloomtrade.trading.domain.Order;
import co.edu.unbosque.bloomtrade.trading.domain.OrderSide;
import co.edu.unbosque.bloomtrade.trading.domain.OrderStatus;
import co.edu.unbosque.bloomtrade.trading.domain.OrderType;
import co.edu.unbosque.bloomtrade.trading.dto.CancelOutcome;
import co.edu.unbosque.bloomtrade.trading.dto.OrderResponse;
import co.edu.unbosque.bloomtrade.trading.event.OrderCancelPendingEvent;
import co.edu.unbosque.bloomtrade.trading.event.OrderCanceledEvent;
import co.edu.unbosque.bloomtrade.trading.event.OrderExecutedEvent;
import co.edu.unbosque.bloomtrade.trading.exception.OrderNotCancelableException;
import co.edu.unbosque.bloomtrade.trading.exception.OrderNotFoundException;
import co.edu.unbosque.bloomtrade.trading.mapper.OrderMapper;
import co.edu.unbosque.bloomtrade.trading.repository.OrderRepository;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests de {@link TradingService#cancelOrder} (HU-F15 Lote B T2.20). Cubre los 13 escenarios
 * críticos del SPEC §11.1: happy BUY/SELL, polling-timeout, race-filled, cross-user, not-cancelable
 * x4 estados, idempotency x2, broker down, drift detected.
 */
@ExtendWith(MockitoExtension.class)
class TradingServiceCancelTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ORDER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID CLIENT_ORDER_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final String ALPACA_ORDER_ID = "alp-xyz-123";

    @Mock private OrderRepository orderRepository;
    @Mock private PortfolioService portfolioService;
    @Mock private UserBalanceRepository userBalanceRepository;
    @Mock private MarketDataAdapter marketDataAdapter;
    @Mock private AlpacaTradingAdapter alpacaTradingAdapter;
    @Mock private CommissionManager commissionManager;
    @Mock private MarketScheduleManager marketScheduleManager;
    @Mock private UserRepository userRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private Auditor auditor;
    @Mock private OrderReconciliationService reconciliationService;

    private TradingService service;
    private OrderMapper orderMapper;

    @BeforeEach
    void setup() {
        orderMapper = new OrderMapper();
        service =
                new TradingService(
                        orderRepository,
                        portfolioService,
                        userBalanceRepository,
                        marketDataAdapter,
                        alpacaTradingAdapter,
                        commissionManager,
                        marketScheduleManager,
                        userRepository,
                        eventPublisher,
                        orderMapper,
                        auditor,
                        reconciliationService,
                        10_000,
                        null);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private Order pendingBuyWithAlpacaId() {
        Order order =
                Order.newPending(
                        USER_ID,
                        CLIENT_ORDER_ID,
                        "AAPL",
                        OrderSide.BUY,
                        OrderType.MARKET,
                        5,
                        new BigDecimal("200.0000"),
                        new BigDecimal("20.00"),
                        new BigDecimal("1020.00"));
        order.linkToAlpaca(ALPACA_ORDER_ID);
        forceId(order, ORDER_ID);
        return order;
    }

    private Order pendingSellWithAlpacaId() {
        Order order =
                Order.newPending(
                        USER_ID,
                        CLIENT_ORDER_ID,
                        "AAPL",
                        OrderSide.SELL,
                        OrderType.MARKET,
                        5,
                        new BigDecimal("200.0000"),
                        new BigDecimal("20.00"),
                        new BigDecimal("980.00"));
        order.linkToAlpaca(ALPACA_ORDER_ID);
        order.linkAvgBuyPriceAtSubmission(new BigDecimal("184.6234"));
        forceId(order, ORDER_ID);
        return order;
    }

    private void stubBalanceLock() {
        UserBalance bal =
                org.mockito.Mockito.mock(UserBalance.class);
        when(userBalanceRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(bal));
    }

    private static void forceId(Order order, UUID id) {
        try {
            Field f = Order.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(order, id);
            Field submittedAt = Order.class.getDeclaredField("submittedAt");
            submittedAt.setAccessible(true);
            submittedAt.set(order, Instant.now());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void forceStatus(Order order, OrderStatus status) {
        try {
            Field f = Order.class.getDeclaredField("status");
            f.setAccessible(true);
            f.set(order, status);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ─── Happy paths ──────────────────────────────────────────────────────────

    @Test
    void cancelBuy_pollingOK_refundsBalanceAndMarksCanceled() {
        stubBalanceLock();
        Order order = pendingBuyWithAlpacaId();
        when(orderRepository.findByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(Optional.of(order));
        when(alpacaTradingAdapter.cancelOrder(ALPACA_ORDER_ID))
                .thenReturn(new CancelOutcome.Canceled(Instant.now()));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(portfolioService.credit(eq(USER_ID), any(BigDecimal.class)))
                .thenReturn(new BigDecimal("8025.50"));

        OrderResponse response = service.cancelOrder(USER_ID, ORDER_ID);

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(response.refundedAmount()).isEqualTo("1020.00");
        assertThat(response.restoredQty()).isNull();
        verify(portfolioService).credit(eq(USER_ID), eq(new BigDecimal("1020.00")));
        verify(portfolioService, never()).upsertPosition(any(), any(), anyInt(), any());

        ArgumentCaptor<OrderCanceledEvent> eventCaptor =
                ArgumentCaptor.forClass(OrderCanceledEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        OrderCanceledEvent event = eventCaptor.getValue();
        assertThat(event.side()).isEqualTo(OrderSide.BUY);
        assertThat(event.refundedAmount()).isEqualTo(new BigDecimal("1020.00"));
        assertThat(event.source()).isEqualTo(OrderCanceledEvent.CancelSource.USER_REQUEST);
    }

    @Test
    void cancelSell_pollingOK_restoresPositionWithAvgBuyPriceSnapshot() {
        stubBalanceLock();
        Order order = pendingSellWithAlpacaId();
        when(orderRepository.findByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(Optional.of(order));
        when(alpacaTradingAdapter.cancelOrder(ALPACA_ORDER_ID))
                .thenReturn(new CancelOutcome.Canceled(Instant.now()));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderResponse response = service.cancelOrder(USER_ID, ORDER_ID);

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(response.refundedAmount()).isNull();
        assertThat(response.restoredQty()).isEqualTo(5);
        // Restore con avg_buy_price_at_submission (no quoted_unit_price).
        verify(portfolioService)
                .upsertPosition(eq(USER_ID), eq("AAPL"), eq(5), eq(new BigDecimal("184.6234")));
        verify(portfolioService, never()).credit(any(), any());
    }

    @Test
    void cancelSellLegacy_withoutAvgBuyPriceSnapshot_fallsBackToQuotedUnitPrice() {
        stubBalanceLock();
        Order order = pendingSellWithAlpacaId();
        // Limpiar el avg_buy_price_at_submission (caso SELL legacy pre-V6).
        try {
            Field f = Order.class.getDeclaredField("avgBuyPriceAtSubmission");
            f.setAccessible(true);
            f.set(order, null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        when(orderRepository.findByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(Optional.of(order));
        when(alpacaTradingAdapter.cancelOrder(ALPACA_ORDER_ID))
                .thenReturn(new CancelOutcome.Canceled(Instant.now()));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        service.cancelOrder(USER_ID, ORDER_ID);

        // Fallback: quoted_unit_price = 200.0000
        verify(portfolioService)
                .upsertPosition(eq(USER_ID), eq("AAPL"), eq(5), eq(new BigDecimal("200.0000")));
    }

    // ─── Polling timeout ─────────────────────────────────────────────────────

    @Test
    void cancel_pollingTimeout_marksCancelRequestedAtAndNoRefund() {
        stubBalanceLock();
        Order order = pendingBuyWithAlpacaId();
        when(orderRepository.findByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(Optional.of(order));
        when(alpacaTradingAdapter.cancelOrder(ALPACA_ORDER_ID))
                .thenReturn(new CancelOutcome.PendingCancel("POLLING_TIMEOUT"));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderResponse response = service.cancelOrder(USER_ID, ORDER_ID);

        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.cancelRequestedAt()).isNotNull();
        assertThat(response.canceledAt()).isNull();
        verify(portfolioService, never()).credit(any(), any());
        verify(portfolioService, never()).upsertPosition(any(), any(), anyInt(), any());

        verify(eventPublisher).publishEvent(any(OrderCancelPendingEvent.class));
    }

    // ─── Race filled ─────────────────────────────────────────────────────────

    @Test
    void cancelBuy_raceFilled_treatsAsExecutedAdjustsBalanceByDelta() {
        stubBalanceLock();
        Order order = pendingBuyWithAlpacaId(); // quotedTotal=1020.00, quotedCommission=20.00
        when(orderRepository.findByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(Optional.of(order));
        // RACE_FILLED: filled_avg_price=210.00 → execution_total = 210*5+20 = 1070.00
        // delta = 1070.00 - 1020.00 = +50.00 → debit extra
        when(alpacaTradingAdapter.cancelOrder(ALPACA_ORDER_ID))
                .thenReturn(
                        new CancelOutcome.RaceFilled(
                                new BigDecimal("210.0000"), 5, Instant.now()));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(portfolioService.debit(eq(USER_ID), any(BigDecimal.class)))
                .thenReturn(new BigDecimal("6930.50"));

        OrderResponse response = service.cancelOrder(USER_ID, ORDER_ID);

        assertThat(response.status()).isEqualTo(OrderStatus.EXECUTED);
        verify(portfolioService).debit(eq(USER_ID), eq(new BigDecimal("50.0000")));
        verify(eventPublisher).publishEvent(any(OrderExecutedEvent.class));
        verify(eventPublisher, never()).publishEvent(any(OrderCanceledEvent.class));
    }

    @Test
    void cancelBuy_raceFilledLowerPrice_refundsDifference() {
        stubBalanceLock();
        Order order = pendingBuyWithAlpacaId();
        // filled_avg_price=190.00 → execution_total = 190*5+20 = 970.00
        // delta = 970.00 - 1020.00 = -50.00 → credit 50
        when(orderRepository.findByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(Optional.of(order));
        when(alpacaTradingAdapter.cancelOrder(ALPACA_ORDER_ID))
                .thenReturn(
                        new CancelOutcome.RaceFilled(
                                new BigDecimal("190.0000"), 5, Instant.now()));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(portfolioService.credit(eq(USER_ID), any(BigDecimal.class)))
                .thenReturn(new BigDecimal("7050.50"));

        OrderResponse response = service.cancelOrder(USER_ID, ORDER_ID);

        assertThat(response.status()).isEqualTo(OrderStatus.EXECUTED);
        verify(portfolioService).credit(eq(USER_ID), eq(new BigDecimal("50.0000")));
    }

    @Test
    void cancelSell_raceFilled_creditsExecutionTotal() {
        stubBalanceLock();
        Order order = pendingSellWithAlpacaId();
        when(orderRepository.findByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(Optional.of(order));
        // SELL filled @ 200.00 → execution_total = 200*5 - 20 = 980.00 (producto neto)
        when(alpacaTradingAdapter.cancelOrder(ALPACA_ORDER_ID))
                .thenReturn(
                        new CancelOutcome.RaceFilled(
                                new BigDecimal("200.0000"), 5, Instant.now()));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(portfolioService.credit(eq(USER_ID), any(BigDecimal.class)))
                .thenReturn(new BigDecimal("8000.00"));

        OrderResponse response = service.cancelOrder(USER_ID, ORDER_ID);

        assertThat(response.status()).isEqualTo(OrderStatus.EXECUTED);
        verify(portfolioService).credit(eq(USER_ID), eq(new BigDecimal("980.0000")));
    }

    // ─── 404 cross-user / NOT_FOUND ───────────────────────────────────────────

    @Test
    void cancel_crossUser_throwsOrderNotFound() {
        stubBalanceLock();
        when(orderRepository.findByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelOrder(USER_ID, ORDER_ID))
                .isInstanceOf(OrderNotFoundException.class);

        verify(alpacaTradingAdapter, never()).cancelOrder(anyString());
        verify(portfolioService, never()).credit(any(), any());
    }

    // ─── 409 NOT_CANCELABLE ───────────────────────────────────────────────────

    @Test
    void cancel_orderExecuted_throws409NotCancelable() {
        stubBalanceLock();
        Order order = pendingBuyWithAlpacaId();
        forceStatus(order, OrderStatus.EXECUTED);
        when(orderRepository.findByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancelOrder(USER_ID, ORDER_ID))
                .isInstanceOf(OrderNotCancelableException.class)
                .satisfies(
                        ex ->
                                assertThat(((OrderNotCancelableException) ex).getCurrentStatus())
                                        .isEqualTo(OrderStatus.EXECUTED));

        verify(alpacaTradingAdapter, never()).cancelOrder(anyString());
    }

    @Test
    void cancel_orderFailed_throws409NotCancelable() {
        stubBalanceLock();
        Order order = pendingBuyWithAlpacaId();
        forceStatus(order, OrderStatus.FAILED);
        when(orderRepository.findByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancelOrder(USER_ID, ORDER_ID))
                .isInstanceOf(OrderNotCancelableException.class);
    }

    // ─── Idempotency ──────────────────────────────────────────────────────────

    @Test
    void cancel_idempotent_secondCallOnCanceled_returnsSameOrderNoSideEffects() {
        stubBalanceLock();
        Order order = pendingBuyWithAlpacaId();
        forceStatus(order, OrderStatus.CANCELED);
        when(orderRepository.findByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(Optional.of(order));

        OrderResponse response = service.cancelOrder(USER_ID, ORDER_ID);

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELED);
        verify(alpacaTradingAdapter, never()).cancelOrder(anyString());
        verify(portfolioService, never()).credit(any(), any());
        // Audit ORDER_DUPLICATE_CANCEL_REQUEST emitido.
        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditor).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().eventType())
                .isEqualTo(AuditEventType.ORDER_DUPLICATE_CANCEL_REQUEST);
    }

    @Test
    void cancel_idempotent_secondCallOnPendingWithCancelRequestedAt_noSecondAlpacaCall() {
        stubBalanceLock();
        Order order = pendingBuyWithAlpacaId();
        order.markCancelRequested();
        when(orderRepository.findByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(Optional.of(order));

        OrderResponse response = service.cancelOrder(USER_ID, ORDER_ID);

        assertThat(response.cancelRequestedAt()).isNotNull();
        verify(alpacaTradingAdapter, never()).cancelOrder(anyString());
    }

    // ─── Broker unavailable ───────────────────────────────────────────────────

    @Test
    void cancel_alpacaDownPostRetries_throws502AndAuditsFailed() {
        stubBalanceLock();
        Order order = pendingBuyWithAlpacaId();
        when(orderRepository.findByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(Optional.of(order));
        when(alpacaTradingAdapter.cancelOrder(ALPACA_ORDER_ID))
                .thenThrow(new AlpacaApiException("Alpaca down tras 3 retries", 3));

        assertThatThrownBy(() -> service.cancelOrder(USER_ID, ORDER_ID))
                .isInstanceOf(AlpacaApiException.class);

        verify(portfolioService, never()).credit(any(), any());
        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditor, times(2)).record(auditCaptor.capture());
        // 1st: ORDER_CANCEL_REQUESTED, 2nd: ORDER_CANCEL_FAILED
        assertThat(auditCaptor.getAllValues().get(1).eventType())
                .isEqualTo(AuditEventType.ORDER_CANCEL_FAILED);
    }

    // ─── Drift detected (Lote C) ──────────────────────────────────────────────

    @Test
    void cancel_alpacaReturns404_driftDetected_reconcileInlineMaterializesCanceled() {
        stubBalanceLock();
        Order order = pendingBuyWithAlpacaId();
        when(orderRepository.findByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(Optional.of(order));
        when(alpacaTradingAdapter.cancelOrder(ALPACA_ORDER_ID))
                .thenThrow(new AlpacaOrderNotFoundException(ALPACA_ORDER_ID));
        Order materialized = pendingBuyWithAlpacaId();
        forceStatus(materialized, OrderStatus.CANCELED);
        when(reconciliationService.applyDriftReconcile(order)).thenReturn(materialized);

        OrderResponse response = service.cancelOrder(USER_ID, ORDER_ID);

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELED);
        // BUY canceled → refundedAmount = quoted_total
        assertThat(response.refundedAmount()).isEqualTo("1020.00");
        // Audit: 2 events — initial ORDER_CANCEL_REQUESTED + drift ORDER_CANCEL_REQUESTED.
        verify(auditor, times(2)).record(any(AuditEvent.class));
    }

    @Test
    void cancel_driftReconcileFails_throws502() {
        stubBalanceLock();
        Order order = pendingBuyWithAlpacaId();
        when(orderRepository.findByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(Optional.of(order));
        when(alpacaTradingAdapter.cancelOrder(ALPACA_ORDER_ID))
                .thenThrow(new AlpacaOrderNotFoundException(ALPACA_ORDER_ID));
        when(reconciliationService.applyDriftReconcile(order))
                .thenThrow(new IllegalStateException("Drift: estado Alpaca no-terminal accepted"));

        assertThatThrownBy(() -> service.cancelOrder(USER_ID, ORDER_ID))
                .isInstanceOf(AlpacaApiException.class)
                .hasMessageContaining("Drift reconcile");
    }
}
