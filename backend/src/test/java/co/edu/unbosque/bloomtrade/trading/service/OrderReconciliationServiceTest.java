package co.edu.unbosque.bloomtrade.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import co.edu.unbosque.bloomtrade.integration.alpaca.AlpacaApiException;
import co.edu.unbosque.bloomtrade.integration.alpaca.AlpacaTradingAdapter;
import co.edu.unbosque.bloomtrade.integration.alpaca.dto.AlpacaOrderResponse;
import co.edu.unbosque.bloomtrade.portfolio.service.PortfolioService;
import co.edu.unbosque.bloomtrade.trading.domain.Order;
import co.edu.unbosque.bloomtrade.trading.domain.OrderSide;
import co.edu.unbosque.bloomtrade.trading.domain.OrderStatus;
import co.edu.unbosque.bloomtrade.trading.domain.OrderType;
import co.edu.unbosque.bloomtrade.trading.repository.OrderRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

/**
 * Unit tests del {@link OrderReconciliationService} con mocks de OrderRepository,
 * AlpacaTradingAdapter, PortfolioService y PlatformTransactionManager.
 *
 * <p>Estrategia: mockear el {@code PlatformTransactionManager} para que el
 * {@code TransactionTemplate} interno ejecute el callback sin transacción real. Verifica
 * la lógica side-aware (BUY upsertPosition vs SELL credit) y los paths de fallo.
 */
@ExtendWith(MockitoExtension.class)
class OrderReconciliationServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String ALPACA_ORDER_ID = "alp_xyz_123";

    @Mock private OrderRepository orderRepository;
    @Mock private AlpacaTradingAdapter alpacaTradingAdapter;
    @Mock private PortfolioService portfolioService;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;
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

    private void stubTxManagerToExecuteCallback() {
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(status);
    }

    private Order pendingOrder(OrderSide side, String ticker, int qty, String quotedTotal) {
        Order order =
                Order.newPending(
                        USER_ID,
                        UUID.randomUUID(),
                        ticker,
                        side,
                        OrderType.MARKET,
                        qty,
                        new BigDecimal("100.00"),
                        new BigDecimal("2.00"),
                        new BigDecimal(quotedTotal));
        order.linkToAlpaca(ALPACA_ORDER_ID);
        return order;
    }

    private AlpacaOrderResponse alpacaResp(String status, BigDecimal filledAvgPrice) {
        return new AlpacaOrderResponse(
                ALPACA_ORDER_ID,
                "client-order",
                "AAPL",
                "1",
                "buy",
                "market",
                status,
                filledAvgPrice,
                "1",
                null,
                null,
                null,
                null, // canceled_at (HU-F15)
                null); // expired_at (HU-F15)
    }

    // ─── reconcilePending ──────────────────────────────────────────────────────

    @Test
    void reconcilePending_noPending_returnsZeroWithoutAlpacaCall() {
        when(orderRepository
                        .findByUserIdAndStatusAndAlpacaOrderIdIsNotNullOrderBySubmittedAtDesc(
                                USER_ID, OrderStatus.PENDING))
                .thenReturn(List.of());

        int result = service.reconcilePending(USER_ID);

        assertThat(result).isZero();
        verifyNoInteractions(alpacaTradingAdapter, portfolioService, transactionManager);
    }

    @Test
    void reconcilePending_buyFilled_materializesAndUpsertsPosition() {
        Order order = pendingOrder(OrderSide.BUY, "AAPL", 1, "102.00");
        when(orderRepository
                        .findByUserIdAndStatusAndAlpacaOrderIdIsNotNullOrderBySubmittedAtDesc(
                                USER_ID, OrderStatus.PENDING))
                .thenReturn(List.of(order));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(alpacaTradingAdapter.getOrder(ALPACA_ORDER_ID))
                .thenReturn(alpacaResp("filled", new BigDecimal("105.50")));
        stubTxManagerToExecuteCallback();

        int result = service.reconcilePending(USER_ID);

        assertThat(result).isEqualTo(1);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.EXECUTED);
        verify(portfolioService)
                .upsertPosition(USER_ID, "AAPL", 1, new BigDecimal("105.50"));
        verify(portfolioService, never()).credit(any(UUID.class), any(BigDecimal.class));
        verify(orderRepository).save(order);
    }

    @Test
    void reconcilePending_sellFilled_creditsBalance() {
        Order order = pendingOrder(OrderSide.SELL, "AAPL", 2, "190.00");
        when(orderRepository
                        .findByUserIdAndStatusAndAlpacaOrderIdIsNotNullOrderBySubmittedAtDesc(
                                USER_ID, OrderStatus.PENDING))
                .thenReturn(List.of(order));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(alpacaTradingAdapter.getOrder(ALPACA_ORDER_ID))
                .thenReturn(alpacaResp("filled", new BigDecimal("98.00")));
        stubTxManagerToExecuteCallback();

        int result = service.reconcilePending(USER_ID);

        assertThat(result).isEqualTo(1);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.EXECUTED);
        verify(portfolioService).credit(USER_ID, new BigDecimal("190.00"));
        verify(portfolioService, never())
                .upsertPosition(any(UUID.class), any(), any(int.class), any(BigDecimal.class));
        verify(orderRepository).save(order);
    }

    @Test
    void reconcilePending_alpacaStillAccepted_keepsPending() {
        Order order = pendingOrder(OrderSide.BUY, "AAPL", 1, "102.00");
        when(orderRepository
                        .findByUserIdAndStatusAndAlpacaOrderIdIsNotNullOrderBySubmittedAtDesc(
                                USER_ID, OrderStatus.PENDING))
                .thenReturn(List.of(order));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(alpacaTradingAdapter.getOrder(ALPACA_ORDER_ID))
                .thenReturn(alpacaResp("accepted", null));
        stubTxManagerToExecuteCallback();

        int result = service.reconcilePending(USER_ID);

        assertThat(result).isZero();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        verify(portfolioService, never())
                .upsertPosition(any(UUID.class), any(), any(int.class), any(BigDecimal.class));
        verify(portfolioService, never()).credit(any(UUID.class), any(BigDecimal.class));
        verify(orderRepository, never()).save(order);
    }

    @Test
    void reconcilePending_alpacaThrows_omitsOrderWithoutPropagating() {
        Order order = pendingOrder(OrderSide.BUY, "AAPL", 1, "102.00");
        when(orderRepository
                        .findByUserIdAndStatusAndAlpacaOrderIdIsNotNullOrderBySubmittedAtDesc(
                                USER_ID, OrderStatus.PENDING))
                .thenReturn(List.of(order));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(alpacaTradingAdapter.getOrder(ALPACA_ORDER_ID))
                .thenThrow(new AlpacaApiException("Alpaca timeout", 3, null));
        stubTxManagerToExecuteCallback();

        int result = service.reconcilePending(USER_ID);

        // No throw + sigue PENDING + no mutaciones; endpoint puede seguir respondiendo.
        assertThat(result).isZero();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        verify(orderRepository, never()).save(order);
    }

    @Test
    void reconcilePending_filledButNoFilledPrice_omits() {
        Order order = pendingOrder(OrderSide.BUY, "AAPL", 1, "102.00");
        when(orderRepository
                        .findByUserIdAndStatusAndAlpacaOrderIdIsNotNullOrderBySubmittedAtDesc(
                                USER_ID, OrderStatus.PENDING))
                .thenReturn(List.of(order));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        // Alpaca dice filled pero sin filledAvgPrice (caso edge no esperado en paper)
        when(alpacaTradingAdapter.getOrder(ALPACA_ORDER_ID))
                .thenReturn(alpacaResp("filled", null));
        stubTxManagerToExecuteCallback();

        int result = service.reconcilePending(USER_ID);

        assertThat(result).isZero();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        verify(orderRepository, never()).save(order);
    }
}
