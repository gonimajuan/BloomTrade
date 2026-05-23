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
import co.edu.unbosque.bloomtrade.auth.domain.DocumentType;
import co.edu.unbosque.bloomtrade.auth.domain.User;
import co.edu.unbosque.bloomtrade.auth.domain.UserStatus;
import co.edu.unbosque.bloomtrade.auth.exception.AccountNotActiveException;
import co.edu.unbosque.bloomtrade.auth.profile.exception.InvalidTickerException;
import co.edu.unbosque.bloomtrade.auth.repository.UserRepository;
import co.edu.unbosque.bloomtrade.integration.alpaca.AlpacaApiException;
import co.edu.unbosque.bloomtrade.integration.alpaca.AlpacaOrderRejectedException;
import co.edu.unbosque.bloomtrade.integration.alpaca.AlpacaTradingAdapter;
import co.edu.unbosque.bloomtrade.integration.alpaca.MarketDataAdapter;
import co.edu.unbosque.bloomtrade.integration.alpaca.dto.AlpacaOrderResponse;
import co.edu.unbosque.bloomtrade.portfolio.exception.InsufficientFundsException;
import co.edu.unbosque.bloomtrade.portfolio.repository.UserBalanceRepository;
import co.edu.unbosque.bloomtrade.portfolio.service.PortfolioService;
import co.edu.unbosque.bloomtrade.trading.domain.Order;
import co.edu.unbosque.bloomtrade.trading.domain.OrderSide;
import co.edu.unbosque.bloomtrade.trading.domain.OrderStatus;
import co.edu.unbosque.bloomtrade.trading.domain.OrderType;
import co.edu.unbosque.bloomtrade.trading.dto.OrderResponse;
import co.edu.unbosque.bloomtrade.trading.dto.PlaceOrderRequest;
import co.edu.unbosque.bloomtrade.trading.dto.QuoteRequest;
import co.edu.unbosque.bloomtrade.trading.dto.QuoteResponse;
import co.edu.unbosque.bloomtrade.trading.event.OrderExecutedEvent;
import co.edu.unbosque.bloomtrade.trading.event.OrderFailedEvent;
import co.edu.unbosque.bloomtrade.trading.event.OrderRejectedEvent;
import co.edu.unbosque.bloomtrade.trading.exception.InvalidQuantityException;
import co.edu.unbosque.bloomtrade.trading.exception.InvalidSideException;
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
 * Unit tests del {@link TradingService} con colaboradores mockeados. Cubre los 10+ escenarios
 * críticos del SPEC §11.1. Los tests llaman {@link TradingService#placeOrderTx} directamente
 * (no {@code placeOrder}) para bypassear el lock + self-proxy — la serialización se valida en
 * {@code TradingServiceConcurrencyIT} con Spring real.
 */
@ExtendWith(MockitoExtension.class)
class TradingServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CLIENT_ORDER_ID = UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d479");

    @Mock private OrderRepository orderRepository;
    @Mock private PortfolioService portfolioService;
    @Mock private UserBalanceRepository userBalanceRepository;
    @Mock private MarketDataAdapter marketDataAdapter;
    @Mock private AlpacaTradingAdapter alpacaTradingAdapter;
    @Mock private CommissionManager commissionManager;
    @Mock private MarketScheduleManager marketScheduleManager;
    @Mock private UserRepository userRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private TradingService service;
    private OrderMapper orderMapper;

    @BeforeEach
    void setup() {
        orderMapper = new OrderMapper();
        // self=null: invocamos placeOrderTx directo (no placeOrder).
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
                        10_000,
                        null);
    }

    // ─── quote ──────────────────────────────────────────────────────────────────

    @Test
    void quote_happyPath_returnsAllFields() {
        stubActiveUser();
        when(marketDataAdapter.getLatestPrice("AAPL")).thenReturn(new BigDecimal("184.5000"));
        when(commissionManager.calculate(eq("INVESTOR"), any(BigDecimal.class)))
                .thenReturn(new BigDecimal("36.90"));
        when(portfolioService.getBalance(USER_ID)).thenReturn(new BigDecimal("10000.00"));
        when(marketScheduleManager.isOpenNow("AAPL")).thenReturn(true);

        QuoteResponse response =
                service.quote(USER_ID, new QuoteRequest("AAPL", OrderSide.BUY, 10));

        assertThat(response.ticker()).isEqualTo("AAPL");
        assertThat(response.estimatedUnitPrice()).isEqualTo("184.5000");
        assertThat(response.estimatedSubtotal()).isEqualTo("1845.00");
        assertThat(response.commission()).isEqualTo("36.90");
        assertThat(response.estimatedTotal()).isEqualTo("1881.90");
        assertThat(response.userBalance()).isEqualTo("10000.00");
        assertThat(response.sufficientFunds()).isTrue();
        assertThat(response.marketOpen()).isTrue();
        assertThat(response.currency()).isEqualTo("USD");
    }

    @Test
    void quote_invalidTicker_throwsBeforeCallingMarketData() {
        assertThatThrownBy(
                        () -> service.quote(USER_ID, new QuoteRequest("GME", OrderSide.BUY, 10)))
                .isInstanceOf(InvalidTickerException.class);
        verify(marketDataAdapter, never()).getLatestPrice(anyString());
    }

    @Test
    void quote_insufficientFunds_returnsSufficientFundsFalseWithoutThrow() {
        stubActiveUser();
        when(marketDataAdapter.getLatestPrice("AAPL")).thenReturn(new BigDecimal("184.5000"));
        when(commissionManager.calculate(eq("INVESTOR"), any(BigDecimal.class)))
                .thenReturn(new BigDecimal("36.90"));
        when(portfolioService.getBalance(USER_ID)).thenReturn(new BigDecimal("100.00"));
        when(marketScheduleManager.isOpenNow("AAPL")).thenReturn(true);

        QuoteResponse response =
                service.quote(USER_ID, new QuoteRequest("AAPL", OrderSide.BUY, 10));

        assertThat(response.sufficientFunds()).isFalse();
        assertThat(response.userBalance()).isEqualTo("100.00");
    }

    @Test
    void quote_sellSide_throwsSideNotYetImplemented() {
        assertThatThrownBy(
                        () -> service.quote(USER_ID, new QuoteRequest("AAPL", OrderSide.SELL, 10)))
                .isInstanceOf(InvalidSideException.class)
                .extracting("errorCode")
                .isEqualTo("SIDE_NOT_YET_IMPLEMENTED");
    }

    // ─── placeOrder ─────────────────────────────────────────────────────────────

    @Test
    void placeOrder_happyPathFilled_executesAndPublishesEvent() {
        stubActiveUser();
        when(orderRepository.findByClientOrderId(CLIENT_ORDER_ID)).thenReturn(Optional.empty());
        when(marketDataAdapter.getLatestPrice("AAPL")).thenReturn(new BigDecimal("184.5000"));
        when(commissionManager.calculate(eq("INVESTOR"), any(BigDecimal.class)))
                .thenReturn(new BigDecimal("36.90"));
        when(userBalanceRepository.findBalanceProjectionByUserId(USER_ID))
                .thenReturn(Optional.of(new BigDecimal("10000.00")));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(inv -> setIdOnOrder(inv.getArgument(0)));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(alpacaTradingAdapter.submitMarketOrder(any()))
                .thenReturn(alpacaFilled("alpaca-xyz", new BigDecimal("184.6200")));
        when(portfolioService.debit(eq(USER_ID), any(BigDecimal.class)))
                .thenReturn(new BigDecimal("8116.90"));

        PlaceOrderResult result =
                service.placeOrderTx(USER_ID, validPlaceOrderRequest());

        assertThat(result.isNew()).isTrue();
        assertThat(result.response().status()).isEqualTo(OrderStatus.EXECUTED);
        assertThat(result.response().executionUnitPrice()).isEqualTo("184.6200");
        verify(portfolioService).debit(eq(USER_ID), any(BigDecimal.class));
        verify(portfolioService)
                .upsertPosition(eq(USER_ID), eq("AAPL"), eq(10), eq(new BigDecimal("184.6200")));

        ArgumentCaptor<OrderExecutedEvent> eventCap = ArgumentCaptor.forClass(OrderExecutedEvent.class);
        verify(eventPublisher).publishEvent(eventCap.capture());
        assertThat(eventCap.getValue().ticker()).isEqualTo("AAPL");
        assertThat(eventCap.getValue().newBalance()).isEqualByComparingTo("8116.90");
    }

    @Test
    void placeOrder_idempotencyReturnsExistingOrderWithoutAlpacaCall() {
        Order existing = newExecutedOrderFixture();
        when(orderRepository.findByClientOrderId(CLIENT_ORDER_ID)).thenReturn(Optional.of(existing));

        PlaceOrderResult result =
                service.placeOrderTx(USER_ID, validPlaceOrderRequest());

        assertThat(result.isNew()).isFalse();
        assertThat(result.response().status()).isEqualTo(OrderStatus.EXECUTED);
        verify(alpacaTradingAdapter, never()).submitMarketOrder(any());
        verify(portfolioService, never()).debit(any(), any());
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    void placeOrder_alpacaRejected_marksRejectedAndDoesNotDebit() {
        stubActiveUser();
        when(orderRepository.findByClientOrderId(CLIENT_ORDER_ID)).thenReturn(Optional.empty());
        when(marketDataAdapter.getLatestPrice("AAPL")).thenReturn(new BigDecimal("184.5000"));
        when(commissionManager.calculate(eq("INVESTOR"), any(BigDecimal.class)))
                .thenReturn(new BigDecimal("36.90"));
        when(userBalanceRepository.findBalanceProjectionByUserId(USER_ID))
                .thenReturn(Optional.of(new BigDecimal("10000.00")));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(inv -> setIdOnOrder(inv.getArgument(0)));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(alpacaTradingAdapter.submitMarketOrder(any()))
                .thenThrow(new AlpacaOrderRejectedException("qty exceeds buying power", "alp-bad"));

        assertThatThrownBy(() -> service.placeOrderTx(USER_ID, validPlaceOrderRequest()))
                .isInstanceOf(AlpacaOrderRejectedException.class);
        verify(portfolioService, never()).debit(any(), any());
        verify(portfolioService, never()).upsertPosition(any(), anyString(), anyInt(), any());

        ArgumentCaptor<OrderRejectedEvent> eventCap = ArgumentCaptor.forClass(OrderRejectedEvent.class);
        verify(eventPublisher).publishEvent(eventCap.capture());
        assertThat(eventCap.getValue().alpacaReason()).isEqualTo("qty exceeds buying power");
    }

    @Test
    void placeOrder_alpacaApiError_marksFailedAndDoesNotDebit() {
        stubActiveUser();
        when(orderRepository.findByClientOrderId(CLIENT_ORDER_ID)).thenReturn(Optional.empty());
        when(marketDataAdapter.getLatestPrice("AAPL")).thenReturn(new BigDecimal("184.5000"));
        when(commissionManager.calculate(eq("INVESTOR"), any(BigDecimal.class)))
                .thenReturn(new BigDecimal("36.90"));
        when(userBalanceRepository.findBalanceProjectionByUserId(USER_ID))
                .thenReturn(Optional.of(new BigDecimal("10000.00")));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(inv -> setIdOnOrder(inv.getArgument(0)));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(alpacaTradingAdapter.submitMarketOrder(any()))
                .thenThrow(new AlpacaApiException("Alpaca down post-retries", 3));

        assertThatThrownBy(() -> service.placeOrderTx(USER_ID, validPlaceOrderRequest()))
                .isInstanceOf(AlpacaApiException.class);
        verify(portfolioService, never()).debit(any(), any());

        ArgumentCaptor<OrderFailedEvent> eventCap = ArgumentCaptor.forClass(OrderFailedEvent.class);
        verify(eventPublisher).publishEvent(eventCap.capture());
        assertThat(eventCap.getValue().errorCode()).isEqualTo("ALPACA_API_ERROR");
    }

    @Test
    void placeOrder_insufficientFundsAtDebit_marksRejectedAndPropagates() {
        stubActiveUser();
        when(orderRepository.findByClientOrderId(CLIENT_ORDER_ID)).thenReturn(Optional.empty());
        when(marketDataAdapter.getLatestPrice("AAPL")).thenReturn(new BigDecimal("184.5000"));
        when(commissionManager.calculate(eq("INVESTOR"), any(BigDecimal.class)))
                .thenReturn(new BigDecimal("36.90"));
        // Pre-check pasa (balance OK vía projection); debit lanza InsuficienteFunds (simula race).
        when(userBalanceRepository.findBalanceProjectionByUserId(USER_ID))
                .thenReturn(Optional.of(new BigDecimal("10000.00")));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(inv -> setIdOnOrder(inv.getArgument(0)));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(alpacaTradingAdapter.submitMarketOrder(any()))
                .thenReturn(alpacaFilled("alpaca-xyz", new BigDecimal("184.6200")));
        when(portfolioService.debit(eq(USER_ID), any(BigDecimal.class)))
                .thenThrow(
                        new InsufficientFundsException(
                                new BigDecimal("100.00"), new BigDecimal("1883.10")));

        assertThatThrownBy(() -> service.placeOrderTx(USER_ID, validPlaceOrderRequest()))
                .isInstanceOf(InsufficientFundsException.class);
        verify(portfolioService, never()).upsertPosition(any(), anyString(), anyInt(), any());

        ArgumentCaptor<OrderRejectedEvent> eventCap = ArgumentCaptor.forClass(OrderRejectedEvent.class);
        verify(eventPublisher).publishEvent(eventCap.capture());
        assertThat(eventCap.getValue().reason()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void placeOrder_accountSuspended_throwsAccountNotActive() {
        User suspended = newUserWithStatus(UserStatus.SUSPENDED);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(suspended));
        when(orderRepository.findByClientOrderId(CLIENT_ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.placeOrderTx(USER_ID, validPlaceOrderRequest()))
                .isInstanceOf(AccountNotActiveException.class);
        verify(alpacaTradingAdapter, never()).submitMarketOrder(any());
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    void placeOrder_invalidQuantityZero_throwsBeforeAlpacaCall() {
        when(orderRepository.findByClientOrderId(CLIENT_ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                service.placeOrderTx(
                                        USER_ID,
                                        new PlaceOrderRequest(
                                                CLIENT_ORDER_ID,
                                                "AAPL",
                                                OrderSide.BUY,
                                                OrderType.MARKET,
                                                0)))
                .isInstanceOf(InvalidQuantityException.class);
        verify(alpacaTradingAdapter, never()).submitMarketOrder(any());
    }

    @Test
    void placeOrder_alpacaAcceptedThenFilledOnPoll_executesNormally() {
        stubActiveUser();
        when(orderRepository.findByClientOrderId(CLIENT_ORDER_ID)).thenReturn(Optional.empty());
        when(marketDataAdapter.getLatestPrice("AAPL")).thenReturn(new BigDecimal("184.5000"));
        when(commissionManager.calculate(eq("INVESTOR"), any(BigDecimal.class)))
                .thenReturn(new BigDecimal("36.90"));
        when(userBalanceRepository.findBalanceProjectionByUserId(USER_ID))
                .thenReturn(Optional.of(new BigDecimal("10000.00")));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(inv -> setIdOnOrder(inv.getArgument(0)));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(alpacaTradingAdapter.submitMarketOrder(any()))
                .thenReturn(alpacaAccepted("alp-pending"));
        when(alpacaTradingAdapter.getOrder("alp-pending"))
                .thenReturn(alpacaFilled("alp-pending", new BigDecimal("184.6200")));
        when(portfolioService.debit(eq(USER_ID), any(BigDecimal.class)))
                .thenReturn(new BigDecimal("8116.90"));

        PlaceOrderResult result = service.placeOrderTx(USER_ID, validPlaceOrderRequest());

        assertThat(result.response().status()).isEqualTo(OrderStatus.EXECUTED);
        verify(alpacaTradingAdapter, times(1)).getOrder("alp-pending");
    }

    // ─── helpers ────────────────────────────────────────────────────────────────

    private void stubActiveUser() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(newUserWithStatus(UserStatus.ACTIVE)));
    }

    private PlaceOrderRequest validPlaceOrderRequest() {
        return new PlaceOrderRequest(CLIENT_ORDER_ID, "AAPL", OrderSide.BUY, OrderType.MARKET, 10);
    }

    private static Order setIdOnOrder(Order order) throws Exception {
        Field idField = Order.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(order, UUID.randomUUID());
        Field submittedAt = Order.class.getDeclaredField("submittedAt");
        submittedAt.setAccessible(true);
        submittedAt.set(order, Instant.now());
        return order;
    }

    private static AlpacaOrderResponse alpacaFilled(String id, BigDecimal price) {
        return new AlpacaOrderResponse(
                id, CLIENT_ORDER_ID.toString(), "AAPL", "10", "buy", "market", "filled",
                price, "10", null, Instant.now().toString(), Instant.now().toString());
    }

    private static AlpacaOrderResponse alpacaAccepted(String id) {
        return new AlpacaOrderResponse(
                id, CLIENT_ORDER_ID.toString(), "AAPL", "10", "buy", "market", "accepted",
                null, "0", null, Instant.now().toString(), null);
    }

    private static User newUserWithStatus(UserStatus status) {
        try {
            User u =
                    User.register(
                            "test-" + UUID.randomUUID() + "@example.com",
                            "$2a$12$dummyhashdummyhashdummyhashdummyhashdummyhashdummyhash",
                            "Test User",
                            DocumentType.CC,
                            "12345678",
                            "+573001112233",
                            Instant.now());
            Field idField = User.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(u, USER_ID);
            Field estadoField = User.class.getDeclaredField("estado");
            estadoField.setAccessible(true);
            estadoField.set(u, status);
            return u;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Order newExecutedOrderFixture() {
        Order o =
                Order.newPending(
                        USER_ID,
                        CLIENT_ORDER_ID,
                        "AAPL",
                        OrderSide.BUY,
                        OrderType.MARKET,
                        10,
                        new BigDecimal("184.5000"),
                        new BigDecimal("36.90"),
                        new BigDecimal("1881.90"));
        o.markAsExecuted("alpaca-xyz", new BigDecimal("184.6200"));
        try {
            setIdOnOrder(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        OrderResponse mapped = orderMapper.toResponse(o);
        assertThat(mapped.status()).isEqualTo(OrderStatus.EXECUTED);
        return o;
    }
}
