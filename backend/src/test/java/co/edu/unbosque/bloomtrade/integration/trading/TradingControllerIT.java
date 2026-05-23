package co.edu.unbosque.bloomtrade.integration.trading;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.WireMock;

import co.edu.unbosque.bloomtrade.audit.Auditor;
import co.edu.unbosque.bloomtrade.auth.domain.DocumentType;
import co.edu.unbosque.bloomtrade.auth.domain.User;
import co.edu.unbosque.bloomtrade.auth.domain.UserStatus;
import co.edu.unbosque.bloomtrade.auth.repository.UserRepository;
import co.edu.unbosque.bloomtrade.auth.security.JwtService;
import co.edu.unbosque.bloomtrade.notification.Notifier;
import co.edu.unbosque.bloomtrade.portfolio.service.BalanceInitializer;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.lang.reflect.Field;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * IT del flujo completo HU-F09 vía {@code POST /api/v1/orders/quote} y {@code POST /api/v1/orders}.
 * Postgres + Spring AOP reales del perfil {@code test}; Alpaca trading+data mockeados con
 * UNA instancia de WireMock — los paths distinguen ({@code /v2/orders} vs
 * {@code /v2/stocks/.../quotes/latest}).
 *
 * <p>Cubre 9 escenarios SPEC §11.1:
 * <ol>
 *   <li>quote happy path</li>
 *   <li>placeOrder happy path filled + persistencia + débito + posición</li>
 *   <li>idempotencia (2da call retorna 200, Alpaca llamado 1 vez)</li>
 *   <li>Alpaca trading down post-retries → 502 + order FAILED + balance intacto</li>
 *   <li>Alpaca rechaza explícitamente → 422 + order REJECTED + balance intacto</li>
 *   <li>Alpaca data down → 502 MARKET_DATA_UNAVAILABLE</li>
 *   <li>fondos insuficientes → 409 + NO order persistida</li>
 *   <li>ticker inválido → 400</li>
 *   <li>cuenta suspended → 403</li>
 * </ol>
 *
 * <p>{@code Notifier} y {@code Auditor} mockeados para no contaminar MailHog/ES en CI.
 * Resilience4j override en {@code application-test.yml} acelera retry-then-fallback.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TradingControllerIT {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    @DynamicPropertySource
    static void registerWireMockUrl(DynamicPropertyRegistry registry) {
        registry.add("alpaca.trading-base-url", wm::baseUrl);
        registry.add("alpaca.data-base-url", wm::baseUrl);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
    @Autowired private BalanceInitializer balanceInitializer;
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
                        "Trader IT",
                        DocumentType.CC,
                        "9000" + System.nanoTime() % 1_000_000,
                        "+573006667788",
                        Instant.now());
        user = userRepository.save(user);
        userId = user.getId();
        balanceInitializer.initializeBalance(userId);
        accessToken = jwtService.generateAccessToken(userId, "INVESTOR");
    }

    // ─── helpers de stubbing ────────────────────────────────────────────────────

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

    private void stubDataDown(String ticker) {
        wm.stubFor(
                get(urlPathEqualTo("/v2/stocks/" + ticker + "/quotes/latest"))
                        .willReturn(aResponse().withStatus(503).withBody("{\"message\":\"down\"}")));
    }

    private void stubTradingFilled(String alpacaOrderId, UUID clientOrderId, String filledAvgPrice) {
        wm.stubFor(
                WireMock.post(urlEqualTo("/v2/orders"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                String.format(
                                                        """
                                                        {"id":"%s","client_order_id":"%s","symbol":"AAPL","qty":"10","side":"buy","type":"market","status":"filled","filled_avg_price":"%s","filled_qty":"10","submitted_at":"2026-05-22T14:32:25Z","filled_at":"2026-05-22T14:32:25Z"}
                                                        """,
                                                        alpacaOrderId, clientOrderId, filledAvgPrice))));
    }

    private void stubTradingRejected(String alpacaOrderId, UUID clientOrderId, String reason) {
        wm.stubFor(
                WireMock.post(urlEqualTo("/v2/orders"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                String.format(
                                                        """
                                                        {"id":"%s","client_order_id":"%s","symbol":"AAPL","qty":"10","side":"buy","type":"market","status":"rejected","rejected_reason":"%s"}
                                                        """,
                                                        alpacaOrderId, clientOrderId, reason))));
    }

    private void stubTradingDown() {
        wm.stubFor(
                WireMock.post(urlEqualTo("/v2/orders"))
                        .willReturn(aResponse().withStatus(503).withBody("{\"message\":\"down\"}")));
    }

    private void overrideBalance(BigDecimal newBalance) {
        jdbc.update(
                "UPDATE app.user_balances SET balance = ? WHERE user_id = ?", newBalance, userId);
    }

    private void suspendUser() throws Exception {
        User u = userRepository.findById(userId).orElseThrow();
        Field estado = User.class.getDeclaredField("estado");
        estado.setAccessible(true);
        estado.set(u, UserStatus.SUSPENDED);
        userRepository.save(u);
    }

    // ─── tests ──────────────────────────────────────────────────────────────────

    @Test
    void quote_happyPath_returns200WithAllFields() throws Exception {
        stubDataLatestQuote("AAPL", "184.52", "184.48");

        mockMvc.perform(
                        post("/api/v1/orders/quote")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"ticker":"AAPL","side":"BUY","quantity":10}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticker").value("AAPL"))
                .andExpect(jsonPath("$.estimatedUnitPrice").value("184.5000"))
                .andExpect(jsonPath("$.sufficientFunds").value(true))
                .andExpect(jsonPath("$.marketOpen").value(true))
                .andExpect(jsonPath("$.userBalance").value("10000.00"))
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void placeOrder_happyPathFilled_returns201AndPersistsOrderDebitsBalanceUpsertsPosition() throws Exception {
        UUID clientOrderId = UUID.randomUUID();
        stubDataLatestQuote("AAPL", "184.52", "184.48");
        stubTradingFilled("alp-1", clientOrderId, "184.6200");

        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/orders")
                                        .header("Authorization", "Bearer " + accessToken)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                String.format(
                                                        """
                                                        {"clientOrderId":"%s","ticker":"AAPL","side":"BUY","type":"MARKET","quantity":10}
                                                        """,
                                                        clientOrderId)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.status").value("EXECUTED"))
                        .andExpect(jsonPath("$.executionUnitPrice").value("184.6200"))
                        .andExpect(jsonPath("$.alpacaOrderId").value("alp-1"))
                        .andReturn();

        // DB state asserts.
        Integer orderCount =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM app.orders WHERE client_order_id = ? AND status = 'EXECUTED'",
                        Integer.class,
                        clientOrderId);
        assertThat(orderCount).isEqualTo(1);

        BigDecimal balance =
                jdbc.queryForObject(
                        "SELECT balance FROM app.user_balances WHERE user_id = ?",
                        BigDecimal.class,
                        userId);
        // 10000 - (184.62 × 10 + 36.90) = 10000 - 1883.10 = 8116.90
        assertThat(balance).isEqualByComparingTo("8116.90");

        Integer positionQty =
                jdbc.queryForObject(
                        "SELECT quantity FROM app.positions WHERE user_id = ? AND ticker = 'AAPL'",
                        Integer.class,
                        userId);
        assertThat(positionQty).isEqualTo(10);
    }

    @Test
    void placeOrder_idempotency_secondCallReturns200WithSameIdAlpacaCalledOnce() throws Exception {
        UUID clientOrderId = UUID.randomUUID();
        stubDataLatestQuote("AAPL", "184.52", "184.48");
        stubTradingFilled("alp-idem", clientOrderId, "184.6200");

        // 1ra request: 201.
        MvcResult first =
                mockMvc.perform(
                                post("/api/v1/orders")
                                        .header("Authorization", "Bearer " + accessToken)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                String.format(
                                                        """
                                                        {"clientOrderId":"%s","ticker":"AAPL","side":"BUY","type":"MARKET","quantity":10}
                                                        """,
                                                        clientOrderId)))
                        .andExpect(status().isCreated())
                        .andReturn();
        String firstId = readJsonPath(first, "id");

        // 2da request mismo clientOrderId: 200 OK con mismo id.
        MvcResult second =
                mockMvc.perform(
                                post("/api/v1/orders")
                                        .header("Authorization", "Bearer " + accessToken)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                String.format(
                                                        """
                                                        {"clientOrderId":"%s","ticker":"AAPL","side":"BUY","type":"MARKET","quantity":10}
                                                        """,
                                                        clientOrderId)))
                        .andExpect(status().isOk())
                        .andReturn();
        String secondId = readJsonPath(second, "id");
        assertThat(secondId).isEqualTo(firstId);

        // Alpaca trading llamado UNA SOLA VEZ.
        wm.verify(exactly(1), postRequestedFor(urlEqualTo("/v2/orders")));

        // Balance descontado UNA sola vez.
        BigDecimal balance =
                jdbc.queryForObject(
                        "SELECT balance FROM app.user_balances WHERE user_id = ?",
                        BigDecimal.class,
                        userId);
        assertThat(balance).isEqualByComparingTo("8116.90");
    }

    @Test
    void placeOrder_alpacaTradingDown_returns502_orderFAILED_balanceUntouched() throws Exception {
        UUID clientOrderId = UUID.randomUUID();
        stubDataLatestQuote("AAPL", "184.52", "184.48");
        stubTradingDown();

        mockMvc.perform(
                        post("/api/v1/orders")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        String.format(
                                                """
                                                {"clientOrderId":"%s","ticker":"AAPL","side":"BUY","type":"MARKET","quantity":10}
                                                """,
                                                clientOrderId)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("ALPACA_API_ERROR"));

        // 3 intentos (max-attempts=3).
        wm.verify(exactly(3), postRequestedFor(urlEqualTo("/v2/orders")));

        String status =
                jdbc.queryForObject(
                        "SELECT status FROM app.orders WHERE client_order_id = ?",
                        String.class,
                        clientOrderId);
        assertThat(status).isEqualTo("FAILED");

        BigDecimal balance =
                jdbc.queryForObject(
                        "SELECT balance FROM app.user_balances WHERE user_id = ?",
                        BigDecimal.class,
                        userId);
        assertThat(balance).isEqualByComparingTo("10000.00");
    }

    @Test
    void placeOrder_alpacaRejected_returns422_orderREJECTED_balanceUntouched() throws Exception {
        UUID clientOrderId = UUID.randomUUID();
        stubDataLatestQuote("AAPL", "184.52", "184.48");
        stubTradingRejected("alp-bad", clientOrderId, "qty exceeds buying power");

        mockMvc.perform(
                        post("/api/v1/orders")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        String.format(
                                                """
                                                {"clientOrderId":"%s","ticker":"AAPL","side":"BUY","type":"MARKET","quantity":10}
                                                """,
                                                clientOrderId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("ALPACA_ORDER_REJECTED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("qty exceeds")));

        // Rejected NO se reintenta (ignore-exceptions).
        wm.verify(exactly(1), postRequestedFor(urlEqualTo("/v2/orders")));

        String status =
                jdbc.queryForObject(
                        "SELECT status FROM app.orders WHERE client_order_id = ?",
                        String.class,
                        clientOrderId);
        assertThat(status).isEqualTo("REJECTED");

        BigDecimal balance =
                jdbc.queryForObject(
                        "SELECT balance FROM app.user_balances WHERE user_id = ?",
                        BigDecimal.class,
                        userId);
        assertThat(balance).isEqualByComparingTo("10000.00");
    }

    @Test
    void placeOrder_marketDataDown_returns502MarketDataUnavailable() throws Exception {
        UUID clientOrderId = UUID.randomUUID();
        stubDataDown("AAPL");

        mockMvc.perform(
                        post("/api/v1/orders")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        String.format(
                                                """
                                                {"clientOrderId":"%s","ticker":"AAPL","side":"BUY","type":"MARKET","quantity":10}
                                                """,
                                                clientOrderId)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("MARKET_DATA_UNAVAILABLE"));

        // Alpaca trading NUNCA fue llamado.
        wm.verify(exactly(0), postRequestedFor(urlEqualTo("/v2/orders")));
    }

    @Test
    void placeOrder_insufficientFunds_returns409_noOrderCreated() throws Exception {
        UUID clientOrderId = UUID.randomUUID();
        overrideBalance(new BigDecimal("100.00"));
        stubDataLatestQuote("AAPL", "184.52", "184.48");

        mockMvc.perform(
                        post("/api/v1/orders")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        String.format(
                                                """
                                                {"clientOrderId":"%s","ticker":"AAPL","side":"BUY","type":"MARKET","quantity":10}
                                                """,
                                                clientOrderId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_FUNDS"));

        // Alpaca NUNCA llamado (validación fonds antes de submit en TradingService).
        wm.verify(exactly(0), postRequestedFor(urlEqualTo("/v2/orders")));

        Integer orderCount =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM app.orders WHERE client_order_id = ?",
                        Integer.class,
                        clientOrderId);
        assertThat(orderCount).isZero();

        BigDecimal balance =
                jdbc.queryForObject(
                        "SELECT balance FROM app.user_balances WHERE user_id = ?",
                        BigDecimal.class,
                        userId);
        assertThat(balance).isEqualByComparingTo("100.00");
    }

    @Test
    void placeOrder_invalidTicker_returns400() throws Exception {
        UUID clientOrderId = UUID.randomUUID();

        mockMvc.perform(
                        post("/api/v1/orders")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        String.format(
                                                """
                                                {"clientOrderId":"%s","ticker":"GME","side":"BUY","type":"MARKET","quantity":10}
                                                """,
                                                clientOrderId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_TICKER"));

        wm.verify(exactly(0), postRequestedFor(urlEqualTo("/v2/orders")));
    }

    @Test
    void placeOrder_suspendedAccount_returns403() throws Exception {
        suspendUser();
        UUID clientOrderId = UUID.randomUUID();
        stubDataLatestQuote("AAPL", "184.52", "184.48");

        mockMvc.perform(
                        post("/api/v1/orders")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        String.format(
                                                """
                                                {"clientOrderId":"%s","ticker":"AAPL","side":"BUY","type":"MARKET","quantity":10}
                                                """,
                                                clientOrderId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCOUNT_NOT_ACTIVE"));

        wm.verify(exactly(0), postRequestedFor(urlEqualTo("/v2/orders")));
    }

    private static String readJsonPath(MvcResult result, String field) throws Exception {
        com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(result.getResponse().getContentAsString());
        return node.path(field).asText();
    }
}
