package co.edu.unbosque.bloomtrade.integration.trading;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import co.edu.unbosque.bloomtrade.audit.Auditor;
import co.edu.unbosque.bloomtrade.auth.domain.DocumentType;
import co.edu.unbosque.bloomtrade.auth.domain.User;
import co.edu.unbosque.bloomtrade.auth.repository.UserRepository;
import co.edu.unbosque.bloomtrade.notification.Notifier;
import co.edu.unbosque.bloomtrade.portfolio.domain.Position;
import co.edu.unbosque.bloomtrade.portfolio.repository.PositionRepository;
import co.edu.unbosque.bloomtrade.portfolio.service.BalanceInitializer;
import co.edu.unbosque.bloomtrade.trading.domain.OrderSide;
import co.edu.unbosque.bloomtrade.trading.domain.OrderType;
import co.edu.unbosque.bloomtrade.trading.dto.PlaceOrderRequest;
import co.edu.unbosque.bloomtrade.trading.exception.InsufficientSharesException;
import co.edu.unbosque.bloomtrade.trading.service.PlaceOrderResult;
import co.edu.unbosque.bloomtrade.trading.service.TradingService;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * IT de concurrencia para el flujo SELL HU-F10 (SPEC §11.1 + §5.3.11/§5.3.12).
 *
 * <ul>
 *   <li>{@link #idempotency_tenSimultaneousSellsSameClientId_resultsInOneDecrement} — 10 hilos
 *       envían el mismo {@code clientOrderId} SELL → 1 fila order, 1 llamada a Alpaca,
 *       1 decremento de posición, 1 crédito de balance.</li>
 *   <li>{@link #concurrency_twoSellsOverlapPosition_exactlyOneSucceeds} — 2 SELLs distintos
 *       que juntos exceden la posición → 1 EXECUTED + 1 INSUFFICIENT_SHARES (lock pessimistic
 *       D12 + invariante quantity >= 0).</li>
 *   <li>{@link #concurrency_buyAndSellSameTicker_bothSucceedWithoutDeadlock} — BUY × 5 y
 *       SELL × 5 concurrentes sobre AAPL: ambas ejecutan sin deadlock. Valida D12 (orden de
 *       adquisición de locks consistente).</li>
 * </ul>
 *
 * <p>Invoca {@link TradingService} directamente (no via MockMvc) para tener acceso a las
 * excepciones tipadas. Postgres + Spring AOP + lock pessimistic reales.
 */
@SpringBootTest
@ActiveProfiles("test")
class TradingServiceSellConcurrencyIT {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    @DynamicPropertySource
    static void registerWireMockUrl(DynamicPropertyRegistry registry) {
        registry.add("alpaca.trading-base-url", wm::baseUrl);
        registry.add("alpaca.data-base-url", wm::baseUrl);
    }

    @Autowired private TradingService tradingService;
    @Autowired private UserRepository userRepository;
    @Autowired private BalanceInitializer balanceInitializer;
    @Autowired private PositionRepository positionRepository;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private Notifier notifier;
    @MockBean private Auditor auditor;

    private UUID userId;

    @BeforeEach
    void setup() {
        wm.resetAll();
        jdbc.execute("TRUNCATE app.orders, app.positions, app.user_balances, app.users CASCADE");
        User user =
                User.register(
                        "sell-conc-" + UUID.randomUUID() + "@test.local",
                        "$2a$12$dummyHashForTestingOnlyNotARealBcryptHashAtAllxx",
                        "Sell Concurrency IT",
                        DocumentType.CC,
                        "6000" + System.nanoTime() % 1_000_000,
                        "+573003334444",
                        Instant.now());
        user = userRepository.save(user);
        userId = user.getId();
        balanceInitializer.initializeBalance(userId);
    }

    @Test
    void idempotency_tenSimultaneousSellsSameClientId_resultsInOneDecrement() throws Exception {
        // Posición inicial: 100 AAPL @ 184.62 (suficiente para que SELLs no fallen por insufficient).
        positionRepository.save(
                Position.newPosition(userId, "AAPL", 100, new BigDecimal("184.6200")));

        UUID clientOrderId = UUID.randomUUID();
        stubDataLatestQuote("AAPL", "190.00", "189.95");
        stubTradingSellFilled("alp-sell-idem", clientOrderId, "189.9500");

        ExecutorService executor = Executors.newFixedThreadPool(10);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger idempotentCount = new AtomicInteger();
        AtomicReference<Throwable> unexpectedError = new AtomicReference<>();

        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                futures.add(
                        CompletableFuture.runAsync(
                                () -> {
                                    try {
                                        PlaceOrderResult result =
                                                tradingService.placeOrder(
                                                        userId,
                                                        new PlaceOrderRequest(
                                                                clientOrderId,
                                                                "AAPL",
                                                                OrderSide.SELL,
                                                                OrderType.MARKET,
                                                                5));
                                        if (result.isNew()) {
                                            successCount.incrementAndGet();
                                        } else {
                                            idempotentCount.incrementAndGet();
                                        }
                                    } catch (Throwable t) {
                                        unexpectedError.compareAndSet(null, t);
                                    }
                                },
                                executor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
        } finally {
            executor.shutdown();
        }

        assertThat(unexpectedError.get()).as("Sin errores inesperados").isNull();
        assertThat(successCount.get()).as("Exactamente UNA orden nueva").isEqualTo(1);
        assertThat(idempotentCount.get()).as("Las otras 9 idempotentes").isEqualTo(9);

        Integer orderRows =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM app.orders WHERE client_order_id = ?",
                        Integer.class,
                        clientOrderId);
        assertThat(orderRows).isEqualTo(1);

        wm.verify(
                com.github.tomakehurst.wiremock.client.WireMock.exactly(1),
                postRequestedFor(urlEqualTo("/v2/orders")));

        // Posición decrementada UNA sola vez: 100 → 95.
        Integer positionQty =
                jdbc.queryForObject(
                        "SELECT quantity FROM app.positions WHERE user_id = ? AND ticker = 'AAPL'",
                        Integer.class,
                        userId);
        assertThat(positionQty).isEqualTo(95);
    }

    @Test
    void concurrency_twoSellsOverlapPosition_exactlyOneSucceeds() throws Exception {
        // Posición: 5 AAPL. Dos SELLs de 3 cada una (total 6 > 5).
        positionRepository.save(
                Position.newPosition(userId, "AAPL", 5, new BigDecimal("184.6200")));

        stubDataLatestQuote("AAPL", "190.00", "189.95");
        UUID clientId1 = UUID.randomUUID();
        UUID clientId2 = UUID.randomUUID();
        stubTradingSellFilledForClient(clientId1, "alp-sell-c1", "189.9500", 3);
        stubTradingSellFilledForClient(clientId2, "alp-sell-c2", "189.9500", 3);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicInteger executedCount = new AtomicInteger();
        AtomicInteger insufficientCount = new AtomicInteger();
        AtomicReference<Throwable> unexpectedError = new AtomicReference<>();

        try {
            CompletableFuture<Void> f1 =
                    launchSell(executor, clientId1, 3, executedCount, insufficientCount, unexpectedError);
            CompletableFuture<Void> f2 =
                    launchSell(executor, clientId2, 3, executedCount, insufficientCount, unexpectedError);
            CompletableFuture.allOf(f1, f2).get();
        } finally {
            executor.shutdown();
        }

        assertThat(unexpectedError.get()).as("Sin errores inesperados").isNull();
        assertThat(executedCount.get()).as("Exactamente UNA ejecutada").isEqualTo(1);
        assertThat(insufficientCount.get()).as("Exactamente UNA insufficient").isEqualTo(1);

        // Posición resultante: 5 - 3 = 2 (la commiteada).
        Integer positionQty =
                jdbc.queryForObject(
                        "SELECT quantity FROM app.positions WHERE user_id = ? AND ticker = 'AAPL'",
                        Integer.class,
                        userId);
        assertThat(positionQty).isEqualTo(2);
    }

    @Test
    void concurrency_buyAndSellSameTicker_bothSucceedWithoutDeadlock() throws Exception {
        // Setup: balance 5000 (suficiente para BUY × 5 ≈ 970), posición 10 AAPL (suficiente
        // para SELL × 5). Lanzamos ambas en paralelo — el lock order D12 garantiza no-deadlock.
        positionRepository.save(
                Position.newPosition(userId, "AAPL", 10, new BigDecimal("184.6200")));
        jdbc.update(
                "UPDATE app.user_balances SET balance = ? WHERE user_id = ?",
                new BigDecimal("5000.00"),
                userId);

        stubDataLatestQuote("AAPL", "190.00", "189.95");
        UUID buyClientId = UUID.randomUUID();
        UUID sellClientId = UUID.randomUUID();
        stubTradingFilledForClient(buyClientId, "alp-buy", "189.9500", 5, "buy");
        stubTradingFilledForClient(sellClientId, "alp-sell", "189.9500", 5, "sell");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicInteger executedCount = new AtomicInteger();
        AtomicReference<Throwable> unexpectedError = new AtomicReference<>();

        try {
            CompletableFuture<Void> buyF =
                    CompletableFuture.runAsync(
                            () -> {
                                try {
                                    tradingService.placeOrder(
                                            userId,
                                            new PlaceOrderRequest(
                                                    buyClientId,
                                                    "AAPL",
                                                    OrderSide.BUY,
                                                    OrderType.MARKET,
                                                    5));
                                    executedCount.incrementAndGet();
                                } catch (Throwable t) {
                                    unexpectedError.compareAndSet(null, t);
                                }
                            },
                            executor);
            CompletableFuture<Void> sellF =
                    CompletableFuture.runAsync(
                            () -> {
                                try {
                                    tradingService.placeOrder(
                                            userId,
                                            new PlaceOrderRequest(
                                                    sellClientId,
                                                    "AAPL",
                                                    OrderSide.SELL,
                                                    OrderType.MARKET,
                                                    5));
                                    executedCount.incrementAndGet();
                                } catch (Throwable t) {
                                    unexpectedError.compareAndSet(null, t);
                                }
                            },
                            executor);
            // Timeout 10s — si hay deadlock, las TXs se quedarían pegadas (lock timeout default
            // de Postgres es alto; un test sano debe ejecutar en <2s).
            CompletableFuture.allOf(buyF, sellF).get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
        }

        assertThat(unexpectedError.get())
                .as("Sin deadlocks ni errores inesperados — D12 lock order respetado")
                .isNull();
        assertThat(executedCount.get()).as("BUY + SELL ambos ejecutados").isEqualTo(2);

        // Estado final: la cantidad de la posición depende del orden de commit entre BUY (+5) y
        // SELL (-5). Posibles: 10+5-5=10 (BUY antes), 10-5+5=10 (SELL antes). En ambos casos = 10.
        Integer positionQty =
                jdbc.queryForObject(
                        "SELECT quantity FROM app.positions WHERE user_id = ? AND ticker = 'AAPL'",
                        Integer.class,
                        userId);
        assertThat(positionQty).isEqualTo(10);
    }

    private CompletableFuture<Void> launchSell(
            ExecutorService executor,
            UUID clientOrderId,
            int qty,
            AtomicInteger executedCount,
            AtomicInteger insufficientCount,
            AtomicReference<Throwable> unexpectedError) {
        return CompletableFuture.runAsync(
                () -> {
                    try {
                        tradingService.placeOrder(
                                userId,
                                new PlaceOrderRequest(
                                        clientOrderId, "AAPL", OrderSide.SELL, OrderType.MARKET, qty));
                        executedCount.incrementAndGet();
                    } catch (InsufficientSharesException e) {
                        insufficientCount.incrementAndGet();
                    } catch (Throwable t) {
                        unexpectedError.compareAndSet(null, t);
                    }
                },
                executor);
    }

    private void stubDataLatestQuote(String ticker, String askPrice, String bidPrice) {
        wm.stubFor(
                get(urlPathEqualTo("/v2/stocks/" + ticker + "/quotes/latest"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                String.format(
                                                        """
                                                        {"symbol":"%s","quote":{"ap":%s,"bp":%s,"as":100,"bs":100,"t":"2026-05-24T14:32:25Z"}}
                                                        """,
                                                        ticker, askPrice, bidPrice))));
    }

    private void stubTradingSellFilled(
            String alpacaOrderId, UUID clientOrderId, String filledAvgPrice) {
        String body =
                String.format(
                        """
                        {"id":"%s","client_order_id":"%s","symbol":"AAPL","qty":"5","side":"sell","type":"market","status":"filled","filled_avg_price":"%s","filled_qty":"5","submitted_at":"2026-05-24T14:32:25Z","filled_at":"2026-05-24T14:32:25Z"}
                        """,
                        alpacaOrderId, clientOrderId, filledAvgPrice);
        wm.stubFor(
                WireMock.post(urlEqualTo("/v2/orders"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(body)));
    }

    private void stubTradingSellFilledForClient(
            UUID clientOrderId, String alpacaOrderId, String filledAvgPrice, int qty) {
        String body =
                String.format(
                        """
                        {"id":"%s","client_order_id":"%s","symbol":"AAPL","qty":"%d","side":"sell","type":"market","status":"filled","filled_avg_price":"%s","filled_qty":"%d","submitted_at":"2026-05-24T14:32:25Z","filled_at":"2026-05-24T14:32:25Z"}
                        """,
                        alpacaOrderId, clientOrderId, qty, filledAvgPrice, qty);
        wm.stubFor(
                WireMock.post(urlEqualTo("/v2/orders"))
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.client_order_id", equalTo(clientOrderId.toString())))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(body)));
    }

    private void stubTradingFilledForClient(
            UUID clientOrderId, String alpacaOrderId, String filledAvgPrice, int qty, String side) {
        String body =
                String.format(
                        """
                        {"id":"%s","client_order_id":"%s","symbol":"AAPL","qty":"%d","side":"%s","type":"market","status":"filled","filled_avg_price":"%s","filled_qty":"%d","submitted_at":"2026-05-24T14:32:25Z","filled_at":"2026-05-24T14:32:25Z"}
                        """,
                        alpacaOrderId, clientOrderId, qty, side, filledAvgPrice, qty);
        wm.stubFor(
                WireMock.post(urlEqualTo("/v2/orders"))
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.client_order_id", equalTo(clientOrderId.toString())))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(body)));
    }
}
