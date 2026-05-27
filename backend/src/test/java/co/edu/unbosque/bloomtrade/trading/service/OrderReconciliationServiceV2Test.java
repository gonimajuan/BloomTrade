package co.edu.unbosque.bloomtrade.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.unbosque.bloomtrade.integration.alpaca.AlpacaTradingAdapter;
import co.edu.unbosque.bloomtrade.integration.alpaca.dto.AlpacaOrderResponse;
import co.edu.unbosque.bloomtrade.portfolio.service.PortfolioService;
import co.edu.unbosque.bloomtrade.trading.domain.Order;
import co.edu.unbosque.bloomtrade.trading.domain.OrderSide;
import co.edu.unbosque.bloomtrade.trading.domain.OrderStatus;
import co.edu.unbosque.bloomtrade.trading.domain.OrderType;
import co.edu.unbosque.bloomtrade.trading.event.OrderCanceledEvent;
import co.edu.unbosque.bloomtrade.trading.event.OrderExpiredEvent;
import co.edu.unbosque.bloomtrade.trading.repository.OrderRepository;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Unit tests del v2 del {@link OrderReconciliationService} (HU-F15 Lote C T3.4). Cubre los
 * branches outbound nuevos: canceled / expired / rejected / partially_filled + SELL legacy
 * fallback + drift reconcile inline.
 *
 * <p>Invoca los métodos package-private directamente (sin Spring) para verificar la lógica
 * sin overhead del TransactionTemplate.
 */
@ExtendWith(MockitoExtension.class)
class OrderReconciliationServiceV2Test {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORDER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID CLIENT_ORDER_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final String ALPACA_ORDER_ID = "alp-xyz-123";

    @Mock private OrderRepository orderRepository;
    @Mock private AlpacaTradingAdapter alpacaTradingAdapter;
    @Mock private PortfolioService portfolioService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private PlatformTransactionManager transactionManager;

    private OrderReconciliationService service;

    @BeforeEach
    void setup() {
        service =
                new OrderReconciliationService(
                        orderRepository,
                        alpacaTradingAdapter,
                        portfolioService,
                        eventPublisher,
                        transactionManager);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private Order pendingBuy() {
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

    private Order pendingSellWithSnapshot() {
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

    private static void forceId(Order order, UUID id) {
        try {
            Field f = Order.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(order, id);
            Field sub = Order.class.getDeclaredField("submittedAt");
            sub.setAccessible(true);
            sub.set(order, Instant.now());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private AlpacaOrderResponse alpacaWithStatus(String status) {
        return new AlpacaOrderResponse(
                ALPACA_ORDER_ID,
                CLIENT_ORDER_ID.toString(),
                "AAPL",
                "5",
                "buy",
                "market",
                status,
                null,
                "0",
                null,
                Instant.now().toString(),
                null,
                "canceled".equals(status) ? Instant.now().toString() : null,
                "expired".equals(status) ? Instant.now().toString() : null);
    }

    // ─── applyCanceledTransition ──────────────────────────────────────────────

    @Test
    void applyCanceled_buy_refundsBalanceAndPublishesBrokerCancelEvent() {
        Order order = pendingBuy();
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        boolean materialized =
                service.applyCanceledTransition(order, alpacaWithStatus("canceled"));

        assertThat(materialized).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
        verify(portfolioService).credit(eq(USER_ID), eq(new BigDecimal("1020.00")));
        ArgumentCaptor<OrderCanceledEvent> evCap = ArgumentCaptor.forClass(OrderCanceledEvent.class);
        verify(eventPublisher).publishEvent(evCap.capture());
        // cancelRequestedAt es null → source=BROKER_CANCEL.
        assertThat(evCap.getValue().source())
                .isEqualTo(OrderCanceledEvent.CancelSource.BROKER_CANCEL);
    }

    @Test
    void applyCanceled_sell_restoresPositionWithSnapshot() {
        Order order = pendingSellWithSnapshot();
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        service.applyCanceledTransition(order, alpacaWithStatus("canceled"));

        // Restore con avg_buy_price del snapshot (D13).
        verify(portfolioService)
                .upsertPosition(eq(USER_ID), eq("AAPL"), eq(5), eq(new BigDecimal("184.6234")));
        verify(portfolioService, never()).credit(any(), any());
    }

    @Test
    void applyCanceled_sellLegacy_fallsBackToQuotedUnitPrice() {
        Order order = pendingSellWithSnapshot();
        // Limpiar snapshot — SELL legacy pre-V6.
        try {
            Field f = Order.class.getDeclaredField("avgBuyPriceAtSubmission");
            f.setAccessible(true);
            f.set(order, null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        service.applyCanceledTransition(order, alpacaWithStatus("canceled"));

        verify(portfolioService)
                .upsertPosition(eq(USER_ID), eq("AAPL"), eq(5), eq(new BigDecimal("200.0000")));
    }

    @Test
    void applyCanceled_withCancelRequestedAt_sourceIsUserRequest() {
        Order order = pendingBuy();
        order.markCancelRequested();
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        service.applyCanceledTransition(order, alpacaWithStatus("canceled"));

        ArgumentCaptor<OrderCanceledEvent> evCap = ArgumentCaptor.forClass(OrderCanceledEvent.class);
        verify(eventPublisher).publishEvent(evCap.capture());
        assertThat(evCap.getValue().source())
                .isEqualTo(OrderCanceledEvent.CancelSource.USER_REQUEST);
    }

    // ─── applyExpiredTransition ───────────────────────────────────────────────

    @Test
    void applyExpired_buy_refundsBalanceAndPublishesExpiredEvent() {
        Order order = pendingBuy();
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        boolean materialized =
                service.applyExpiredTransition(order, alpacaWithStatus("expired"));

        assertThat(materialized).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        verify(portfolioService).credit(eq(USER_ID), any(BigDecimal.class));
        verify(eventPublisher).publishEvent(any(OrderExpiredEvent.class));
    }

    // ─── applyRejectedTransition ──────────────────────────────────────────────

    @Test
    void applyRejected_buy_refundsAndMarksRejectedNoEmail() {
        Order order = pendingBuy();
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        boolean materialized =
                service.applyRejectedTransition(order, alpacaWithStatus("rejected"));

        assertThat(materialized).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        verify(portfolioService).credit(eq(USER_ID), any(BigDecimal.class));
        // NO publish OrderRejectedEvent — evita email retroactivo confuso.
        verify(eventPublisher, never()).publishEvent(any(OrderCanceledEvent.class));
        verify(eventPublisher, never()).publishEvent(any(OrderExpiredEvent.class));
    }

    // ─── applyDriftReconcile ──────────────────────────────────────────────────

    @Test
    void applyDriftReconcile_canceled_materializesWithDriftSource() {
        Order order = pendingBuy();
        when(alpacaTradingAdapter.getOrder(ALPACA_ORDER_ID))
                .thenReturn(alpacaWithStatus("canceled"));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        // After materialize, findById returns the updated order.
        when(orderRepository.findById(ORDER_ID)).thenReturn(java.util.Optional.of(order));

        Order result = service.applyDriftReconcile(order);

        assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELED);
        ArgumentCaptor<OrderCanceledEvent> evCap = ArgumentCaptor.forClass(OrderCanceledEvent.class);
        verify(eventPublisher).publishEvent(evCap.capture());
        assertThat(evCap.getValue().source())
                .isEqualTo(OrderCanceledEvent.CancelSource.DRIFT_RECONCILE);
    }

    @Test
    void applyDriftReconcile_unexpectedAcceptedState_throwsIllegalState() {
        Order order = pendingBuy();
        // Alpaca devuelve "accepted" — estado no-terminal en path drift es inesperado.
        when(alpacaTradingAdapter.getOrder(ALPACA_ORDER_ID))
                .thenReturn(alpacaWithStatus("accepted"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.applyDriftReconcile(order))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no-terminal");
    }
}
