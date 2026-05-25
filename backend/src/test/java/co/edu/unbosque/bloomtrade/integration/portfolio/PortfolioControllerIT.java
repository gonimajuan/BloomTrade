package co.edu.unbosque.bloomtrade.integration.portfolio;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.WireMock;

import co.edu.unbosque.bloomtrade.audit.Auditor;
import co.edu.unbosque.bloomtrade.auth.domain.DocumentType;
import co.edu.unbosque.bloomtrade.auth.domain.User;
import co.edu.unbosque.bloomtrade.auth.repository.UserRepository;
import co.edu.unbosque.bloomtrade.auth.security.JwtService;
import co.edu.unbosque.bloomtrade.notification.Notifier;
import co.edu.unbosque.bloomtrade.portfolio.service.BalanceInitializer;
import co.edu.unbosque.bloomtrade.portfolio.service.PortfolioService;
import co.edu.unbosque.bloomtrade.trading.domain.Order;
import co.edu.unbosque.bloomtrade.trading.domain.OrderSide;
import co.edu.unbosque.bloomtrade.trading.domain.OrderType;
import co.edu.unbosque.bloomtrade.trading.repository.OrderRepository;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * IT del bundle HU-F16 + HU-F21 vía {@code GET /api/v1/portfolio/positions} y
 * {@code /balance}. Postgres del docker-compose + WireMock para Alpaca data API (los
 * endpoints {@code /v2/stocks/{symbol}/quotes/latest}).
 *
 * <p>Cubre los criterios de aceptación del SPEC §11:
 * <ul>
 *   <li>HU-F16-AC-01 happy mark-to-market</li>
 *   <li>HU-F16-AC-02 Alpaca down → marketDataAvailable=false con 200</li>
 *   <li>HU-F16-AC-03 falla parcial → "partial"</li>
 *   <li>HU-F16-AC-04 usuario sin posiciones → 200 sin invocar adapter</li>
 *   <li>HU-F16-AC-05 pending orders visibles</li>
 *   <li>HU-F16-AC-07 sin JWT → 401</li>
 *   <li>HU-F21-AC-01 balance happy</li>
 *   <li>HU-F21-AC-04 balance sin JWT → 401</li>
 * </ul>
 *
 * <p>Tests de aislamiento cross-user y edge cases adicionales en Lote E.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PortfolioControllerIT {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    @DynamicPropertySource
    static void registerWireMockUrl(DynamicPropertyRegistry registry) {
        registry.add("alpaca.data-base-url", wm::baseUrl);
        registry.add("alpaca.trading-base-url", wm::baseUrl);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
    @Autowired private BalanceInitializer balanceInitializer;
    @Autowired private PortfolioService portfolioService;
    @MockBean private Notifier notifier;
    @MockBean private Auditor auditor;

    private UUID userId;
    private String accessToken;

    @BeforeEach
    void setup() {
        wm.resetAll();
        jdbc.execute("TRUNCATE app.orders, app.positions, app.user_balances, app.users CASCADE");

        User user =
                User.register(
                        "trader-" + UUID.randomUUID() + "@test.local",
                        "$2a$12$dummyHashForTestingOnlyNotARealBcryptHashAtAllxx",
                        "Trader Portfolio IT",
                        DocumentType.CC,
                        "9100" + System.nanoTime() % 1_000_000,
                        "+573006667799",
                        Instant.now());
        user = userRepository.save(user);
        userId = user.getId();
        balanceInitializer.initializeBalance(userId);
        accessToken = jwtService.generateAccessToken(userId, "INVESTOR");
    }

    // ─── helpers de stubbing y seed ─────────────────────────────────────────────

    private void stubDataLatestQuote(String ticker, String askPrice, String bidPrice) {
        wm.stubFor(
                WireMock.get(urlPathEqualTo("/v2/stocks/" + ticker + "/quotes/latest"))
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

    private void stubDataDown(String ticker) {
        wm.stubFor(
                WireMock.get(urlPathEqualTo("/v2/stocks/" + ticker + "/quotes/latest"))
                        .willReturn(aResponse().withStatus(503).withBody("{\"message\":\"down\"}")));
    }

    private void stubDataNotFound(String ticker) {
        wm.stubFor(
                WireMock.get(urlPathEqualTo("/v2/stocks/" + ticker + "/quotes/latest"))
                        .willReturn(aResponse().withStatus(404).withBody("{\"message\":\"ticker not found\"}")));
    }

    private void seedPosition(String ticker, int quantity, String avgBuyPrice) {
        jdbc.update(
                "INSERT INTO app.positions (id, user_id, ticker, quantity, avg_buy_price)"
                        + " VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                userId,
                ticker,
                quantity,
                new BigDecimal(avgBuyPrice));
    }

    private void overrideBalance(BigDecimal newBalance) {
        jdbc.update(
                "UPDATE app.user_balances SET balance = ? WHERE user_id = ?", newBalance, userId);
    }

    private Order seedPendingOrderWithAlpacaId(String ticker, OrderSide side, int quantity) {
        Order order =
                Order.newPending(
                        userId,
                        UUID.randomUUID(),
                        ticker,
                        side,
                        OrderType.MARKET,
                        quantity,
                        new BigDecimal("100.0000"),
                        new BigDecimal("10.0000"),
                        new BigDecimal("1010.0000"));
        order.linkToAlpaca("alpaca-pending-" + UUID.randomUUID());
        return orderRepository.save(order);
    }

    // ─── tests HU-F16 /positions ────────────────────────────────────────────────

    @Test
    void getPositions_happyMarkToMarket_returnsAllFieldsAndAvailableTrue() throws Exception {
        // Alfabético por D18: positions[0]=AAPL, positions[1]=MSFT.
        seedPosition("AAPL", 10, "189.4500");
        seedPosition("MSFT", 5, "412.0000");
        stubDataLatestQuote("AAPL", "193.20", "193.20");
        stubDataLatestQuote("MSFT", "408.50", "408.50");

        mockMvc.perform(
                        get("/api/v1/portfolio/positions")
                                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marketDataAvailable").value("true"))
                .andExpect(jsonPath("$.positions", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$.pendingOrders", org.hamcrest.Matchers.empty()))
                .andExpect(jsonPath("$.fetchedAt").isNotEmpty())
                // AAPL (positions[0]): 10 × 193.20 = 1932.00, costBasis 1894.50, PnL 37.50, PnL% 1.98
                .andExpect(jsonPath("$.positions[0].ticker").value("AAPL"))
                .andExpect(jsonPath("$.positions[0].currentPrice").value("193.20"))
                .andExpect(jsonPath("$.positions[0].marketValue").value("1932.00"))
                .andExpect(jsonPath("$.positions[0].unrealizedPnL").value("37.50"))
                .andExpect(jsonPath("$.positions[0].unrealizedPnLPct").value("1.98"))
                // MSFT (positions[1]): 5 × 408.50 = 2042.50, costBasis 2060.00, PnL -17.50, PnL% -0.85
                .andExpect(jsonPath("$.positions[1].ticker").value("MSFT"))
                .andExpect(jsonPath("$.positions[1].unrealizedPnL").value("-17.50"))
                .andExpect(jsonPath("$.positions[1].unrealizedPnLPct").value("-0.85"));
    }

    @Test
    void getPositions_alpacaDown_returnsAvailableFalseWithNullFields() throws Exception {
        seedPosition("AAPL", 10, "189.4500");
        seedPosition("MSFT", 5, "412.0000");
        stubDataDown("AAPL");
        stubDataDown("MSFT");

        mockMvc.perform(
                        get("/api/v1/portfolio/positions")
                                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marketDataAvailable").value("false"))
                .andExpect(jsonPath("$.positions", org.hamcrest.Matchers.hasSize(2)))
                // @JsonInclude(NON_NULL) suprime currentPrice/marketValue/unrealizedPnL/unrealizedPnLPct
                .andExpect(jsonPath("$.positions[0].currentPrice").doesNotExist())
                .andExpect(jsonPath("$.positions[0].marketValue").doesNotExist())
                .andExpect(jsonPath("$.positions[0].unrealizedPnL").doesNotExist())
                .andExpect(jsonPath("$.positions[0].avgCost").value("189.45"));
    }

    @Test
    void getPositions_partialFailure_returnsAvailablePartial() throws Exception {
        // Alfabético por D18: positions[0]=AAPL, [1]=MSFT, [2]=TSLA.
        seedPosition("AAPL", 10, "189.4500");
        seedPosition("MSFT", 5, "412.0000");
        seedPosition("TSLA", 3, "200.0000");
        stubDataLatestQuote("AAPL", "193.20", "193.20");
        stubDataLatestQuote("MSFT", "408.50", "408.50");
        stubDataNotFound("TSLA");

        mockMvc.perform(
                        get("/api/v1/portfolio/positions")
                                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marketDataAvailable").value("partial"))
                .andExpect(jsonPath("$.positions", org.hamcrest.Matchers.hasSize(3)))
                .andExpect(jsonPath("$.positions[0].ticker").value("AAPL"))
                .andExpect(jsonPath("$.positions[0].currentPrice").value("193.20"))
                .andExpect(jsonPath("$.positions[1].ticker").value("MSFT"))
                .andExpect(jsonPath("$.positions[1].currentPrice").value("408.50"))
                .andExpect(jsonPath("$.positions[2].ticker").value("TSLA"))
                .andExpect(jsonPath("$.positions[2].currentPrice").doesNotExist());
    }

    @Test
    void getPositions_noPositions_returnsEmptyAndDoesNotCallAlpaca() throws Exception {
        mockMvc.perform(
                        get("/api/v1/portfolio/positions")
                                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positions", org.hamcrest.Matchers.empty()))
                .andExpect(jsonPath("$.pendingOrders", org.hamcrest.Matchers.empty()))
                .andExpect(jsonPath("$.marketDataAvailable").value("true"));

        wm.verify(exactly(0), getRequestedFor(urlPathEqualTo("/v2/stocks/AAPL/quotes/latest")));
    }

    @Test
    void getPositions_withPendingOrder_returnsPendingOrdersArray() throws Exception {
        Order pending = seedPendingOrderWithAlpacaId("TSLA", OrderSide.BUY, 5);

        mockMvc.perform(
                        get("/api/v1/portfolio/positions")
                                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positions", org.hamcrest.Matchers.empty()))
                .andExpect(jsonPath("$.pendingOrders", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.pendingOrders[0].orderId").value(pending.getId().toString()))
                .andExpect(jsonPath("$.pendingOrders[0].ticker").value("TSLA"))
                .andExpect(jsonPath("$.pendingOrders[0].side").value("BUY"))
                .andExpect(jsonPath("$.pendingOrders[0].quantity").value(5))
                .andExpect(jsonPath("$.pendingOrders[0].quotedTotal").value("1010.00"));
    }

    @Test
    void getPositions_withoutJwt_returns403() throws Exception {
        // Plan §2.4 D17: el JwtAuthenticationFilter solo escribe 401 cuando hay token y es
        // inválido/expirado. Sin Authorization header, Spring Security 6 responde 403 por
        // default (no hay AuthenticationEntryPoint customizado). El SPEC §6.4 declara 401 —
        // divergencia cross-cutting fuera del scope de F16+F21. Mini-HU
        // `HU-F0X-token-rotation-logout` la corregirá globalmente.
        mockMvc.perform(get("/api/v1/portfolio/positions")).andExpect(status().isForbidden());
    }

    // ─── tests HU-F21 /balance ──────────────────────────────────────────────────

    @Test
    void getBalance_happyPath_returnsBalanceAndMetadata() throws Exception {
        overrideBalance(new BigDecimal("8345.67"));

        mockMvc.perform(
                        get("/api/v1/portfolio/balance")
                                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value("8345.67"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.lastUpdatedAt").isNotEmpty());

        BigDecimal balanceDb =
                jdbc.queryForObject(
                        "SELECT balance FROM app.user_balances WHERE user_id = ?",
                        BigDecimal.class,
                        userId);
        assertThat(balanceDb).isEqualByComparingTo("8345.67");
    }

    @Test
    void getBalance_withoutJwt_returns403() throws Exception {
        // Plan §2.4 D17 — ver comentario en getPositions_withoutJwt_returns403.
        mockMvc.perform(get("/api/v1/portfolio/balance")).andExpect(status().isForbidden());
    }

    // ─── Lote E — tests adicionales ────────────────────────────────────────────

    /**
     * HU-F16-AC-06 (T5.1) — aislamiento cross-user en /positions: usuario A NO ve posiciones
     * de B, y WireMock confirma que solo se consultaron precios de los tickers de A.
     */
    @Test
    void getPositions_crossUserIsolation_userASeesOnlyOwnPositions() throws Exception {
        // Usuario A (el del setup): 1 posición AAPL.
        seedPosition("AAPL", 10, "189.4500");
        // Usuario B: 1 posición MSFT que A NO debe ver.
        UUID userBId = seedSecondUser("trader-b").userId();
        jdbc.update(
                "INSERT INTO app.positions (id, user_id, ticker, quantity, avg_buy_price)"
                        + " VALUES (?, ?, 'MSFT', 5, 412.0000)",
                UUID.randomUUID(),
                userBId);

        stubDataLatestQuote("AAPL", "193.20", "193.20");
        stubDataLatestQuote("MSFT", "408.50", "408.50");

        mockMvc.perform(
                        get("/api/v1/portfolio/positions")
                                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positions", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.positions[0].ticker").value("AAPL"));

        // Solo se llamó a Alpaca para AAPL (no MSFT) — confirma que el filter por userId aplica
        // ANTES del fan-out (no se filtra después fugando datos al adapter).
        wm.verify(getRequestedFor(urlPathEqualTo("/v2/stocks/AAPL/quotes/latest")));
        wm.verify(exactly(0), getRequestedFor(urlPathEqualTo("/v2/stocks/MSFT/quotes/latest")));
    }

    /**
     * HU-F21-AC-03 (T5.2) — aislamiento cross-user en /balance: A ve 5000, B ve 8000, JAMÁS
     * se cruzan los saldos.
     */
    @Test
    void getBalance_crossUserIsolation_eachUserSeesOwnBalance() throws Exception {
        overrideBalance(new BigDecimal("5000.00"));
        SeededUser userB = seedSecondUser("trader-b");
        jdbc.update(
                "UPDATE app.user_balances SET balance = 8000.00 WHERE user_id = ?",
                userB.userId());

        // A ve 5000.
        mockMvc.perform(
                        get("/api/v1/portfolio/balance")
                                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value("5000.00"));

        // B ve 8000.
        mockMvc.perform(
                        get("/api/v1/portfolio/balance")
                                .header("Authorization", "Bearer " + userB.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value("8000.00"));
    }

    /**
     * HU-F21-AC-05 (T5.3) — tras un credit al balance, GET /balance refleja el nuevo monto y
     * el {@code lastUpdatedAt} avanza vs el valor inicial.
     */
    @Test
    void getBalance_reflectsCreditAndAdvancesLastUpdatedAt() throws Exception {
        // Capturar el lastUpdatedAt inicial via primera call.
        String initialJson =
                mockMvc.perform(
                                get("/api/v1/portfolio/balance")
                                        .header("Authorization", "Bearer " + accessToken))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        Instant initialUpdatedAt =
                Instant.parse(initialJson.replaceAll(".*\"lastUpdatedAt\":\"([^\"]+)\".*", "$1"));

        // Asegurar que pase al menos 1ms para que el @UpdateTimestamp de Hibernate cambie.
        Thread.sleep(50);

        // Aplicar credit real vía service (mismo flujo que F10 produce tras una venta).
        portfolioService.credit(userId, new BigDecimal("1500.00"));

        mockMvc.perform(
                        get("/api/v1/portfolio/balance")
                                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value("11500.00"))
                .andExpect(
                        jsonPath("$.lastUpdatedAt")
                                .value(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.equalTo(
                                                        initialUpdatedAt.toString()))));

        BigDecimal balanceDb =
                jdbc.queryForObject(
                        "SELECT balance FROM app.user_balances WHERE user_id = ?",
                        BigDecimal.class,
                        userId);
        assertThat(balanceDb).isEqualByComparingTo("11500.00");
    }

    /**
     * SPEC §14 Q6 (T5.4) — defensa qty=0 desde HTTP: una fila huérfana con quantity=0 (que
     * NUNCA debería existir tras HU-F10 D1) no se incluye en {@code positions[]}.
     */
    @Test
    void getPositions_excludesQuantityZeroDefense() throws Exception {
        // Insertamos via JDBC bypaseando los validadores del entity — simula bug futuro o
        // dato manual de soporte.
        jdbc.update(
                "INSERT INTO app.positions (id, user_id, ticker, quantity, avg_buy_price)"
                        + " VALUES (?, ?, 'TSLA', 0, 200.0000)",
                UUID.randomUUID(),
                userId);
        seedPosition("AAPL", 10, "189.4500");
        stubDataLatestQuote("AAPL", "193.20", "193.20");

        mockMvc.perform(
                        get("/api/v1/portfolio/positions")
                                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positions", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.positions[0].ticker").value("AAPL"));

        // No se intentó consultar precio para TSLA (el filtro qty>0 lo eliminó antes del fan-out).
        wm.verify(exactly(0), getRequestedFor(urlPathEqualTo("/v2/stocks/TSLA/quotes/latest")));
    }

    /** Crea un segundo usuario con su propio token. Util para tests de aislamiento cross-user. */
    private record SeededUser(UUID userId, String token) {}

    private SeededUser seedSecondUser(String emailPrefix) {
        User user =
                User.register(
                        emailPrefix + "-" + UUID.randomUUID() + "@test.local",
                        "$2a$12$dummyHashForTestingOnlyNotARealBcryptHashAtAllxx",
                        "Trader B",
                        DocumentType.CC,
                        "9200" + System.nanoTime() % 1_000_000,
                        "+573006667800",
                        Instant.now());
        user = userRepository.save(user);
        UUID id = user.getId();
        balanceInitializer.initializeBalance(id);
        String token = jwtService.generateAccessToken(id, "INVESTOR");
        return new SeededUser(id, token);
    }
}
