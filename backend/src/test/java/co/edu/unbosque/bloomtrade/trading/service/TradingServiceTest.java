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
import co.edu.unbosque.bloomtrade.audit.Auditor;
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
import co.edu.unbosque.bloomtrade.portfolio.domain.Position;
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
import co.edu.unbosque.bloomtrade.trading.exception.InsufficientSharesException;
import co.edu.unbosque.bloomtrade.trading.exception.InvalidQuantityException;
import co.edu.unbosque.bloomtrade.trading.exception.ShortSellingNotAllowedException;
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
    @Mock private Auditor auditor;
    @Mock private OrderReconciliationService reconciliationService;

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
                        auditor,
                        reconciliationService,
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

    // HU-F10: SELL ya no lanza SIDE_NOT_YET_IMPLEMENTED — el quote responde con
    // sufficientShares/userShares y estimatedTotal = subtotal − commission (producto neto).

    @Test
    void quote_sellSideWithSufficientPosition_returnsSufficientSharesTrueAndNetProceeds() {
        stubActiveUser();
        when(marketDataAdapter.getLatestPrice("AAPL")).thenReturn(new BigDecimal("190.0000"));
        when(commissionManager.calculate(eq("INVESTOR"), any(BigDecimal.class)))
                .thenReturn(new BigDecimal("19.00"));
        when(portfolioService.getBalance(USER_ID)).thenReturn(new BigDecimal("8116.90"));
        when(marketScheduleManager.isOpenNow("AAPL")).thenReturn(true);
        Position pos = Position.newPosition(USER_ID, "AAPL", 10, new BigDecimal("184.6200"));
        when(portfolioService.findPosition(USER_ID, "AAPL")).thenReturn(Optional.of(pos));

        QuoteResponse response =
                service.quote(USER_ID, new QuoteRequest("AAPL", OrderSide.SELL, 5));

        assertThat(response.side()).isEqualTo(OrderSide.SELL);
        // subtotal = 5 × 190 = 950; estimatedTotal SELL = 950 − 19 = 931 (producto neto)
        assertThat(response.estimatedSubtotal()).isEqualTo("950.00");
        assertThat(response.commission()).isEqualTo("19.00");
        assertThat(response.estimatedTotal()).isEqualTo("931.00");
        assertThat(response.sufficientShares()).isTrue();
        assertThat(response.userShares()).isEqualTo(10);
        assertThat(response.sufficientFunds()).isTrue(); // vender NUNCA descuenta balance
    }

    @Test
    void quote_sellSideWithoutPosition_returnsSufficientSharesFalseAndZeroShares() {
        stubActiveUser();
        when(marketDataAdapter.getLatestPrice("MSFT")).thenReturn(new BigDecimal("420.0000"));
        when(commissionManager.calculate(eq("INVESTOR"), any(BigDecimal.class)))
                .thenReturn(new BigDecimal("8.40"));
        when(portfolioService.getBalance(USER_ID)).thenReturn(new BigDecimal("8116.90"));
        when(marketScheduleManager.isOpenNow("MSFT")).thenReturn(true);
        when(portfolioService.findPosition(USER_ID, "MSFT")).thenReturn(Optional.empty());

        QuoteResponse response =
                service.quote(USER_ID, new QuoteRequest("MSFT", OrderSide.SELL, 1));

        assertThat(response.sufficientShares()).isFalse();
        assertThat(response.userShares()).isEqualTo(0);
        assertThat(response.estimatedTotal()).isEqualTo("411.60"); // 420 − 8.40
    }

    @Test
    void quote_sellSideWithInsufficientPosition_returnsSufficientSharesFalseWithUserShares() {
        stubActiveUser();
        when(marketDataAdapter.getLatestPrice("AAPL")).thenReturn(new BigDecimal("190.0000"));
        when(commissionManager.calculate(eq("INVESTOR"), any(BigDecimal.class)))
                .thenReturn(new BigDecimal("19.00"));
        when(portfolioService.getBalance(USER_ID)).thenReturn(new BigDecimal("8116.90"));
        when(marketScheduleManager.isOpenNow("AAPL")).thenReturn(true);
        Position pos = Position.newPosition(USER_ID, "AAPL", 3, new BigDecimal("184.6200"));
        when(portfolioService.findPosition(USER_ID, "AAPL")).thenReturn(Optional.of(pos));

        QuoteResponse response =
                service.quote(USER_ID, new QuoteRequest("AAPL", OrderSide.SELL, 5));

        assertThat(response.sufficientShares()).isFalse();
        assertThat(response.userShares()).isEqualTo(3);
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
        // HU-F10: upsertPosition ahora retorna Position (TradingService usa qty post-upsert para
        // OrderExecutedEvent.positionResultingQty).
        when(portfolioService.upsertPosition(
                        eq(USER_ID), eq("AAPL"), eq(10), eq(new BigDecimal("184.6200"))))
                .thenReturn(Position.newPosition(USER_ID, "AAPL", 10, new BigDecimal("184.6200")));

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
        assertThat(eventCap.getValue().side()).isEqualTo(OrderSide.BUY);
        assertThat(eventCap.getValue().newBalance()).isEqualByComparingTo("8116.90");
        assertThat(eventCap.getValue().positionResultingQty()).isEqualTo(10);
        assertThat(eventCap.getValue().positionDeleted()).isFalse();
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
        when(portfolioService.upsertPosition(
                        eq(USER_ID), eq("AAPL"), eq(10), eq(new BigDecimal("184.6200"))))
                .thenReturn(Position.newPosition(USER_ID, "AAPL", 10, new BigDecimal("184.6200")));

        PlaceOrderResult result = service.placeOrderTx(USER_ID, validPlaceOrderRequest());

        assertThat(result.response().status()).isEqualTo(OrderStatus.EXECUTED);
        verify(alpacaTradingAdapter, times(1)).getOrder("alp-pending");
    }

    // ─── placeOrder SELL (HU-F10) ──────────────────────────────────────────────

    @Test
    void placeOrder_sellHappyPath_executesDecrementAndCredits() {
        stubActiveUser();
        when(orderRepository.findByClientOrderId(CLIENT_ORDER_ID)).thenReturn(Optional.empty());
        when(marketDataAdapter.getLatestPrice("AAPL")).thenReturn(new BigDecimal("190.0000"));
        when(commissionManager.calculate(eq("INVESTOR"), any(BigDecimal.class)))
                .thenReturn(new BigDecimal("19.00"));
        Position lockedPos = Position.newPosition(USER_ID, "AAPL", 10, new BigDecimal("184.6200"));
        when(portfolioService.validateSellable(USER_ID, "AAPL", 5)).thenReturn(lockedPos);
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(inv -> setIdOnOrder(inv.getArgument(0)));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(alpacaTradingAdapter.submitMarketOrder(any()))
                .thenReturn(alpacaSellFilled("alp-sell-1", new BigDecimal("189.9500")));
        Position residual = Position.newPosition(USER_ID, "AAPL", 5, new BigDecimal("184.6200"));
        when(portfolioService.decrementPosition(USER_ID, "AAPL", 5))
                .thenReturn(Optional.of(residual));
        when(portfolioService.credit(eq(USER_ID), any(BigDecimal.class)))
                .thenReturn(new BigDecimal("9047.65"));

        PlaceOrderResult result = service.placeOrderTx(USER_ID, sellPlaceOrderRequest(5));

        assertThat(result.isNew()).isTrue();
        assertThat(result.response().status()).isEqualTo(OrderStatus.EXECUTED);
        assertThat(result.response().side()).isEqualTo(OrderSide.SELL);
        // SELL executionTotal = 5 × 189.95 − 19.00 = 949.75 − 19.00 = 930.75
        assertThat(result.response().executionTotal()).isEqualTo("930.7500");
        verify(portfolioService).decrementPosition(USER_ID, "AAPL", 5);
        verify(portfolioService).credit(eq(USER_ID), any(BigDecimal.class));
        verify(portfolioService, never()).debit(any(), any());
        verify(portfolioService, never()).upsertPosition(any(), anyString(), anyInt(), any());

        ArgumentCaptor<OrderExecutedEvent> eventCap = ArgumentCaptor.forClass(OrderExecutedEvent.class);
        verify(eventPublisher).publishEvent(eventCap.capture());
        OrderExecutedEvent captured = eventCap.getValue();
        assertThat(captured.side()).isEqualTo(OrderSide.SELL);
        assertThat(captured.positionResultingQty()).isEqualTo(5);
        assertThat(captured.positionDeleted()).isFalse();
        assertThat(captured.newBalance()).isEqualByComparingTo("9047.65");
    }

    @Test
    void placeOrder_sellExactQty_deletesPositionAndEmitsPositionDeletedTrue() {
        stubActiveUser();
        when(orderRepository.findByClientOrderId(CLIENT_ORDER_ID)).thenReturn(Optional.empty());
        when(marketDataAdapter.getLatestPrice("AAPL")).thenReturn(new BigDecimal("190.0000"));
        when(commissionManager.calculate(eq("INVESTOR"), any(BigDecimal.class)))
                .thenReturn(new BigDecimal("19.00"));
        Position lockedPos = Position.newPosition(USER_ID, "AAPL", 5, new BigDecimal("184.6200"));
        when(portfolioService.validateSellable(USER_ID, "AAPL", 5)).thenReturn(lockedPos);
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(inv -> setIdOnOrder(inv.getArgument(0)));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(alpacaTradingAdapter.submitMarketOrder(any()))
                .thenReturn(alpacaSellFilled("alp-sell-2", new BigDecimal("189.9500")));
        // Decrement total → Optional.empty() (fila borrada por D1).
        when(portfolioService.decrementPosition(USER_ID, "AAPL", 5)).thenReturn(Optional.empty());
        when(portfolioService.credit(eq(USER_ID), any(BigDecimal.class)))
                .thenReturn(new BigDecimal("9047.65"));

        service.placeOrderTx(USER_ID, sellPlaceOrderRequest(5));

        ArgumentCaptor<OrderExecutedEvent> eventCap = ArgumentCaptor.forClass(OrderExecutedEvent.class);
        verify(eventPublisher).publishEvent(eventCap.capture());
        OrderExecutedEvent captured = eventCap.getValue();
        assertThat(captured.positionResultingQty()).isEqualTo(0);
        assertThat(captured.positionDeleted()).isTrue();
    }

    @Test
    void placeOrder_sellShortSelling_throws409_alpacaNeverCalled() {
        stubActiveUser();
        when(orderRepository.findByClientOrderId(CLIENT_ORDER_ID)).thenReturn(Optional.empty());
        when(marketDataAdapter.getLatestPrice("AAPL")).thenReturn(new BigDecimal("190.0000"));
        when(commissionManager.calculate(eq("INVESTOR"), any(BigDecimal.class)))
                .thenReturn(new BigDecimal("19.00"));
        when(portfolioService.validateSellable(USER_ID, "AAPL", 5))
                .thenThrow(new ShortSellingNotAllowedException("AAPL", 5));

        assertThatThrownBy(() -> service.placeOrderTx(USER_ID, sellPlaceOrderRequest(5)))
                .isInstanceOf(ShortSellingNotAllowedException.class);

        verify(alpacaTradingAdapter, never()).submitMarketOrder(any());
        verify(orderRepository, never()).saveAndFlush(any());
        verify(portfolioService, never()).decrementPosition(any(), anyString(), anyInt());
        verify(portfolioService, never()).credit(any(), any());
    }

    @Test
    void placeOrder_sellInsufficientShares_throws409_alpacaNeverCalled() {
        stubActiveUser();
        when(orderRepository.findByClientOrderId(CLIENT_ORDER_ID)).thenReturn(Optional.empty());
        when(marketDataAdapter.getLatestPrice("AAPL")).thenReturn(new BigDecimal("190.0000"));
        when(commissionManager.calculate(eq("INVESTOR"), any(BigDecimal.class)))
                .thenReturn(new BigDecimal("19.00"));
        when(portfolioService.validateSellable(USER_ID, "AAPL", 5))
                .thenThrow(new InsufficientSharesException(3, 5, "AAPL"));

        assertThatThrownBy(() -> service.placeOrderTx(USER_ID, sellPlaceOrderRequest(5)))
                .isInstanceOf(InsufficientSharesException.class);

        verify(alpacaTradingAdapter, never()).submitMarketOrder(any());
        verify(portfolioService, never()).credit(any(), any());
    }

    @Test
    void placeOrder_sellAlpacaRejected_marksRejected_positionUntouched() {
        stubActiveUser();
        when(orderRepository.findByClientOrderId(CLIENT_ORDER_ID)).thenReturn(Optional.empty());
        when(marketDataAdapter.getLatestPrice("AAPL")).thenReturn(new BigDecimal("190.0000"));
        when(commissionManager.calculate(eq("INVESTOR"), any(BigDecimal.class)))
                .thenReturn(new BigDecimal("19.00"));
        Position lockedPos = Position.newPosition(USER_ID, "AAPL", 10, new BigDecimal("184.6200"));
        when(portfolioService.validateSellable(USER_ID, "AAPL", 5)).thenReturn(lockedPos);
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(inv -> setIdOnOrder(inv.getArgument(0)));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(alpacaTradingAdapter.submitMarketOrder(any()))
                .thenThrow(new AlpacaOrderRejectedException("asset not tradeable", "alp-rej"));

        assertThatThrownBy(() -> service.placeOrderTx(USER_ID, sellPlaceOrderRequest(5)))
                .isInstanceOf(AlpacaOrderRejectedException.class);

        // Posición NO se decrementó ni balance acreditado.
        verify(portfolioService, never()).decrementPosition(any(), anyString(), anyInt());
        verify(portfolioService, never()).credit(any(), any());

        ArgumentCaptor<OrderRejectedEvent> eventCap = ArgumentCaptor.forClass(OrderRejectedEvent.class);
        verify(eventPublisher).publishEvent(eventCap.capture());
        assertThat(eventCap.getValue().side()).isEqualTo(OrderSide.SELL);
        assertThat(eventCap.getValue().alpacaReason()).isEqualTo("asset not tradeable");
    }

    @Test
    void placeOrder_sellAlpacaApiError_marksFailed_positionUntouched() {
        stubActiveUser();
        when(orderRepository.findByClientOrderId(CLIENT_ORDER_ID)).thenReturn(Optional.empty());
        when(marketDataAdapter.getLatestPrice("AAPL")).thenReturn(new BigDecimal("190.0000"));
        when(commissionManager.calculate(eq("INVESTOR"), any(BigDecimal.class)))
                .thenReturn(new BigDecimal("19.00"));
        Position lockedPos = Position.newPosition(USER_ID, "AAPL", 10, new BigDecimal("184.6200"));
        when(portfolioService.validateSellable(USER_ID, "AAPL", 5)).thenReturn(lockedPos);
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(inv -> setIdOnOrder(inv.getArgument(0)));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(alpacaTradingAdapter.submitMarketOrder(any()))
                .thenThrow(new AlpacaApiException("Alpaca down post-retries", 3));

        assertThatThrownBy(() -> service.placeOrderTx(USER_ID, sellPlaceOrderRequest(5)))
                .isInstanceOf(AlpacaApiException.class);

        verify(portfolioService, never()).decrementPosition(any(), anyString(), anyInt());
        verify(portfolioService, never()).credit(any(), any());

        ArgumentCaptor<OrderFailedEvent> eventCap = ArgumentCaptor.forClass(OrderFailedEvent.class);
        verify(eventPublisher).publishEvent(eventCap.capture());
        assertThat(eventCap.getValue().side()).isEqualTo(OrderSide.SELL);
        assertThat(eventCap.getValue().errorCode()).isEqualTo("ALPACA_API_ERROR");
    }

    // ─── helpers ────────────────────────────────────────────────────────────────

    private void stubActiveUser() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(newUserWithStatus(UserStatus.ACTIVE)));
    }

    private PlaceOrderRequest validPlaceOrderRequest() {
        return new PlaceOrderRequest(CLIENT_ORDER_ID, "AAPL", OrderSide.BUY, OrderType.MARKET, 10);
    }

    private PlaceOrderRequest sellPlaceOrderRequest(int qty) {
        return new PlaceOrderRequest(CLIENT_ORDER_ID, "AAPL", OrderSide.SELL, OrderType.MARKET, qty);
    }

    private static AlpacaOrderResponse alpacaSellFilled(String id, BigDecimal price) {
        return new AlpacaOrderResponse(
                id,
                CLIENT_ORDER_ID.toString(),
                "AAPL",
                "5",
                "sell",
                "market",
                "filled",
                price,
                "5",
                null,
                Instant.now().toString(),
                Instant.now().toString(),
                null, // canceled_at (HU-F15)
                null); // expired_at (HU-F15)
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
                price, "10", null, Instant.now().toString(), Instant.now().toString(),
                null, null); // canceled_at + expired_at (HU-F15)
    }

    private static AlpacaOrderResponse alpacaAccepted(String id) {
        return new AlpacaOrderResponse(
                id, CLIENT_ORDER_ID.toString(), "AAPL", "10", "buy", "market", "accepted",
                null, "0", null, Instant.now().toString(), null,
                null, null); // canceled_at + expired_at (HU-F15)
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
