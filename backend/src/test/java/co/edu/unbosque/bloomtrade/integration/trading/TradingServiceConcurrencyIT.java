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
import co.edu.unbosque.bloomtrade.portfolio.exception.InsufficientFundsException;
import co.edu.unbosque.bloomtrade.portfolio.service.BalanceInitializer;
import co.edu.unbosque.bloomtrade.trading.domain.OrderSide;
import co.edu.unbosque.bloomtrade.trading.domain.OrderType;
import co.edu.unbosque.bloomtrade.trading.dto.PlaceOrderRequest;
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
 * IT de concurrencia del flujo de orden (HU-F09 SPEC §11.1 "Concurrencia"). Dos escenarios:
 * <ul>
 *   <li>{@link #idempotency_tenSimultaneousRequestsSameClientId_resultsInOneRow} — 10 hilos
 *       envían el mismo {@code clientOrderId} → exactamente 1 orden creada, 1 llamada a Alpaca,
 *       9 respuestas idempotentes.</li>
 *   <li>{@link #concurrency_twoOrdersOverlapBalance_exactlyOneSucceeds} — 2 órdenes distintas
 *       cuyo total combinado excede el saldo → 1 EXECUTED + 1 InsufficientFunds.</li>
 * </ul>
 *
 * <p>Invoca {@link TradingService} directamente (no via MockMvc) para tener acceso a las
 * excepciones tipadas. Postgres + Spring AOP + lock pessimistic reales.
 */
@SpringBootTest
@ActiveProfiles("test")
class TradingServiceConcurrencyIT {

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
                        "conc-" + UUID.randomUUID() + "@test.local",
                        "$2a$12$dummyHashForTestingOnlyNotARealBcryptHashAtAllxx",
                        "Concurrency IT",
                        DocumentType.CC,
                        "7000" + System.nanoTime() % 1_000_000,
                        "+573002221111",
                        Instant.now());
        user = userRepository.save(user);
        userId = user.getId();
        balanceInitializer.initializeBalance(userId);
    }

    @Test
    void idempotency_tenSimultaneousRequestsSameClientId_resultsInOneRow() throws Exception {
        UUID clientOrderId = UUID.randomUUID();
        stubDataLatestQuote("AAPL", "184.52", "184.48");
        stubTradingFilled("alp-idem", clientOrderId, "184.6200");

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
                                                                OrderSide.BUY,
                                                                OrderType.MARKET,
                                                                10));
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
        // Exactamente 1 creó la orden; los otros 9 vieron idempotencia.
        assertThat(successCount.get() + idempotentCount.get()).isEqualTo(10);
        assertThat(successCount.get()).isEqualTo(1);

        // En la BD: 1 sola fila para ese clientOrderId.
        Integer orderRows =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM app.orders WHERE client_order_id = ?",
                        Integer.class,
                        clientOrderId);
        assertThat(orderRows).isEqualTo(1);

        // Alpaca trading llamado UNA SOLA VEZ (no 10).
        wm.verify(
                com.github.tomakehurst.wiremock.client.WireMock.exactly(1),
                postRequestedFor(urlEqualTo("/v2/orders")));

        // Balance descontado UNA sola vez: 10000 - 1883.10 = 8116.90.
        BigDecimal balance =
                jdbc.queryForObject(
                        "SELECT balance FROM app.user_balances WHERE user_id = ?",
                        BigDecimal.class,
                        userId);
        assertThat(balance).isEqualByComparingTo("8116.90");
    }

    @Test
    void concurrency_twoOrdersOverlapBalance_exactlyOneSucceeds() throws Exception {
        // Balance: 2000 (override). Dos órdenes de 10 AAPL @ 184.50 + comisión = ~1881.90 c/u.
        // Combined: 3763.80 > 2000.
        jdbc.update(
                "UPDATE app.user_balances SET balance = ? WHERE user_id = ?",
                new BigDecimal("2000.00"),
                userId);

        stubDataLatestQuote("AAPL", "184.52", "184.48");

        UUID clientId1 = UUID.randomUUID();
        UUID clientId2 = UUID.randomUUID();
        // 2 stubs distintos por client_order_id en el body → alpacaOrderId distinto cada uno.
        // Sin esto, ambas filas tratarían de tomar el mismo idx_orders_alpaca_order_id UNIQUE.
        stubTradingFilledForClient(clientId1, "alp-conc-1", "184.6200");
        stubTradingFilledForClient(clientId2, "alp-conc-2", "184.6200");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicInteger executedCount = new AtomicInteger();
        AtomicInteger insufficientCount = new AtomicInteger();
        AtomicReference<Throwable> unexpectedError = new AtomicReference<>();

        try {
            CompletableFuture<Void> f1 = launch(executor, clientId1, executedCount, insufficientCount, unexpectedError);
            CompletableFuture<Void> f2 = launch(executor, clientId2, executedCount, insufficientCount, unexpectedError);
            CompletableFuture.allOf(f1, f2).get();
        } finally {
            executor.shutdown();
        }

        assertThat(unexpectedError.get()).as("Sin errores inesperados").isNull();
        assertThat(executedCount.get()).as("Exactamente UNA ejecutada").isEqualTo(1);
        assertThat(insufficientCount.get()).as("Exactamente UNA insufficient").isEqualTo(1);

        // Balance = 2000 - 1883.10 = 116.90 (solo la commiteada).
        BigDecimal balance =
                jdbc.queryForObject(
                        "SELECT balance FROM app.user_balances WHERE user_id = ?",
                        BigDecimal.class,
                        userId);
        assertThat(balance).isEqualByComparingTo("116.90");
    }

    private CompletableFuture<Void> launch(
            ExecutorService executor,
            UUID clientOrderId,
            AtomicInteger executedCount,
            AtomicInteger insufficientCount,
            AtomicReference<Throwable> unexpectedError) {
        return CompletableFuture.runAsync(
                () -> {
                    try {
                        tradingService.placeOrder(
                                userId,
                                new PlaceOrderRequest(
                                        clientOrderId, "AAPL", OrderSide.BUY, OrderType.MARKET, 10));
                        executedCount.incrementAndGet();
                    } catch (InsufficientFundsException e) {
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
                                                        {"symbol":"%s","quote":{"ap":%s,"bp":%s,"as":100,"bs":100,"t":"2026-05-22T14:32:25Z"}}
                                                        """,
                                                        ticker, askPrice, bidPrice))));
    }

    private void stubTradingFilled(String alpacaOrderId, UUID clientOrderId, String filledAvgPrice) {
        String body =
                String.format(
                        """
                        {"id":"%s","client_order_id":"%s","symbol":"AAPL","qty":"10","side":"buy","type":"market","status":"filled","filled_avg_price":"%s","filled_qty":"10","submitted_at":"2026-05-22T14:32:25Z","filled_at":"2026-05-22T14:32:25Z"}
                        """,
                        alpacaOrderId,
                        clientOrderId != null ? clientOrderId.toString() : "any",
                        filledAvgPrice);
        wm.stubFor(
                WireMock.post(urlEqualTo("/v2/orders"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(body)));
    }

    private void stubTradingFilledForClient(
            UUID clientOrderId, String alpacaOrderId, String filledAvgPrice) {
        String body =
                String.format(
                        """
                        {"id":"%s","client_order_id":"%s","symbol":"AAPL","qty":"10","side":"buy","type":"market","status":"filled","filled_avg_price":"%s","filled_qty":"10","submitted_at":"2026-05-22T14:32:25Z","filled_at":"2026-05-22T14:32:25Z"}
                        """,
                        alpacaOrderId, clientOrderId, filledAvgPrice);
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
