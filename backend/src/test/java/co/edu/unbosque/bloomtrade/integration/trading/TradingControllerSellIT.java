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

import co.edu.unbosque.bloomtrade.audit.Auditor;
import co.edu.unbosque.bloomtrade.auth.domain.DocumentType;
import co.edu.unbosque.bloomtrade.auth.domain.User;
import co.edu.unbosque.bloomtrade.auth.repository.UserRepository;
import co.edu.unbosque.bloomtrade.auth.security.JwtService;
import co.edu.unbosque.bloomtrade.notification.Notifier;
import co.edu.unbosque.bloomtrade.portfolio.domain.Position;
import co.edu.unbosque.bloomtrade.portfolio.repository.PositionRepository;
import co.edu.unbosque.bloomtrade.portfolio.service.BalanceInitializer;
import com.github.tomakehurst.wiremock.client.WireMock;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * IT del flujo de venta Market HU-F10 vía {@code POST /api/v1/orders/quote} y
 * {@code POST /api/v1/orders} con {@code side=SELL}. Postgres + Spring AOP reales del perfil
 * {@code test}; Alpaca trading+data mockeados con UNA instancia de WireMock.
 *
 * <p>Setup: usuario con balance 8000 USD + posición {@code AAPL × 10 @ 184.62} insertada
 * directamente vía {@link PositionRepository} en {@code @BeforeEach}.
 *
 * <p>Cubre 9 escenarios SPEC F10 §11.1:
 * <ol>
 *   <li>quote SELL happy path → sufficientShares=true, userShares=10, estimatedTotal=net</li>
 *   <li>quote SELL sin posición → sufficientShares=false, userShares=0 (informativo)</li>
 *   <li>placeOrder SELL filled → 201 + position decrementada + balance creditado</li>
 *   <li>placeOrder SELL liquidación total → DELETE de la fila position + balance creditado</li>
 *   <li>placeOrder SELL sin posición → 409 SHORT_SELLING_NOT_ALLOWED + Alpaca NO invocado</li>
 *   <li>placeOrder SELL cantidad insuficiente → 409 INSUFFICIENT_SHARES + Alpaca NO invocado</li>
 *   <li>placeOrder SELL Alpaca rechaza → 422 + posición y balance intactos</li>
 *   <li>placeOrder SELL Alpaca down → 502 + posición y balance intactos</li>
 *   <li>placeOrder SELL idempotencia: 2da call con mismo clientOrderId → 200 + decrement 1 vez</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TradingControllerSellIT {

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
    @Autowired private PositionRepository positionRepository;
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
                        "seller-" + UUID.randomUUID() + "@test.local",
                        "$2a$12$dummyHashForTestingOnlyNotARealBcryptHashAtAllxx",
                        "Seller IT",
                        DocumentType.CC,
                        "8000" + System.nanoTime() % 1_000_000,
                        "+573006669999",
                        Instant.now());
        user = userRepository.save(user);
        userId = user.getId();
        balanceInitializer.initializeBalance(userId);
        // Balance default 10000; ajustamos a 8116.90 para reflejar estado post-BUY del demo F09.
        jdbc.update(
                "UPDATE app.user_balances SET balance = ? WHERE user_id = ?",
                new BigDecimal("8116.90"),
                userId);
        // Posición inicial: 10 AAPL @ 184.62 (la del demo F09).
        positionRepository.save(
                Position.newPosition(userId, "AAPL", 10, new BigDecimal("184.6200")));
        accessToken = jwtService.generateAccessToken(userId, "INVESTOR");
    }

    // ─── stubbing helpers ───────────────────────────────────────────────────────

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
                                                        {"symbol":"%s","quote":{"ap":%s,"bp":%s,"as":100,"bs":100,"t":"2026-05-23T14:32:25Z"}}
                                                        """,
                                                        ticker, askPrice, bidPrice))));
    }

    private void stubTradingSellFilled(
            String alpacaOrderId, UUID clientOrderId, int qty, String filledAvgPrice) {
        wm.stubFor(
                WireMock.post(urlEqualTo("/v2/orders"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                String.format(
                                                        """
                                                        {"id":"%s","client_order_id":"%s","symbol":"AAPL","qty":"%d","side":"sell","type":"market","status":"filled","filled_avg_price":"%s","filled_qty":"%d","submitted_at":"2026-05-23T14:32:25Z","filled_at":"2026-05-23T14:32:25Z"}
                                                        """,
                                                        alpacaOrderId,
                                                        clientOrderId,
                                                        qty,
                                                        filledAvgPrice,
                                                        qty))));
    }

    private void stubTradingSellRejected(String alpacaOrderId, UUID clientOrderId, String reason) {
        wm.stubFor(
                WireMock.post(urlEqualTo("/v2/orders"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                String.format(
                                                        """
                                                        {"id":"%s","client_order_id":"%s","symbol":"AAPL","qty":"5","side":"sell","type":"market","status":"rejected","rejected_reason":"%s"}
                                                        """,
                                                        alpacaOrderId, clientOrderId, reason))));
    }

    private void stubTradingDown() {
        wm.stubFor(
                WireMock.post(urlEqualTo("/v2/orders"))
                        .willReturn(aResponse().withStatus(503).withBody("{\"message\":\"down\"}")));
    }

    // ─── tests ──────────────────────────────────────────────────────────────────

    @Test
    void quote_sellHappyPath_returns200WithSufficientSharesAndNetProceeds() throws Exception {
        stubDataLatestQuote("AAPL", "190.00", "189.95");

        mockMvc.perform(
                        post("/api/v1/orders/quote")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"ticker":"AAPL","side":"SELL","quantity":5}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.side").value("SELL"))
                .andExpect(jsonPath("$.estimatedUnitPrice").value("189.9750"))
                .andExpect(jsonPath("$.estimatedSubtotal").value("949.88"))
                .andExpect(jsonPath("$.sufficientShares").value(true))
                .andExpect(jsonPath("$.userShares").value(10))
                .andExpect(jsonPath("$.sufficientFunds").value(true))
                .andExpect(jsonPath("$.marketOpen").value(true));
    }

    @Test
    void quote_sellWithoutPosition_returnsSufficientSharesFalseAndZeroShares() throws Exception {
        stubDataLatestQuote("MSFT", "420.00", "419.50");

        mockMvc.perform(
                        post("/api/v1/orders/quote")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"ticker":"MSFT","side":"SELL","quantity":1}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sufficientShares").value(false))
                .andExpect(jsonPath("$.userShares").value(0));
    }

    @Test
    void placeOrder_sellHappyPath_returns201_decrementsPositionAndCreditsBalance() throws Exception {
        UUID clientOrderId = UUID.randomUUID();
        stubDataLatestQuote("AAPL", "190.00", "189.95");
        stubTradingSellFilled("alp-sell-1", clientOrderId, 5, "189.9500");

        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/orders")
                                        .header("Authorization", "Bearer " + accessToken)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                String.format(
                                                        """
                                                        {"clientOrderId":"%s","ticker":"AAPL","side":"SELL","type":"MARKET","quantity":5}
                                                        """,
                                                        clientOrderId)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.status").value("EXECUTED"))
                        .andExpect(jsonPath("$.side").value("SELL"))
                        .andExpect(jsonPath("$.executionUnitPrice").value("189.9500"))
                        .andExpect(jsonPath("$.alpacaOrderId").value("alp-sell-1"))
                        .andReturn();

        // app.orders: 1 fila SELL EXECUTED.
        Integer orderCount =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM app.orders WHERE client_order_id = ? AND side = 'SELL' AND status = 'EXECUTED'",
                        Integer.class,
                        clientOrderId);
        assertThat(orderCount).isEqualTo(1);

        // app.positions: qty decrementada de 10 a 5; avg_buy_price preservado.
        Integer positionQty =
                jdbc.queryForObject(
                        "SELECT quantity FROM app.positions WHERE user_id = ? AND ticker = 'AAPL'",
                        Integer.class,
                        userId);
        assertThat(positionQty).isEqualTo(5);
        BigDecimal avgBuy =
                jdbc.queryForObject(
                        "SELECT avg_buy_price FROM app.positions WHERE user_id = ? AND ticker = 'AAPL'",
                        BigDecimal.class,
                        userId);
        assertThat(avgBuy).isEqualByComparingTo("184.6200");

        // app.user_balances: 8116.90 + (5 × 189.95 − 19.00) = 8116.90 + 930.75 = 9047.65
        // Pero la comisión la calcula el backend con HALF_UP scale=2 sobre subtotal 949.88 = 18.9976 → 19.00
        // executionTotal = 5 × 189.95 − 19.00 = 930.75
        BigDecimal balance =
                jdbc.queryForObject(
                        "SELECT balance FROM app.user_balances WHERE user_id = ?",
                        BigDecimal.class,
                        userId);
        assertThat(balance).isEqualByComparingTo("9047.65");
    }

    @Test
    void placeOrder_sellTotalLiquidation_deletesPositionRow_andCreditsBalance() throws Exception {
        UUID clientOrderId = UUID.randomUUID();
        stubDataLatestQuote("AAPL", "190.00", "189.95");
        stubTradingSellFilled("alp-sell-2", clientOrderId, 10, "189.9500");

        mockMvc.perform(
                        post("/api/v1/orders")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        String.format(
                                                """
                                                {"clientOrderId":"%s","ticker":"AAPL","side":"SELL","type":"MARKET","quantity":10}
                                                """,
                                                clientOrderId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EXECUTED"));

        // app.positions: fila BORRADA por D1 (qty resultante = 0).
        Integer positionCount =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM app.positions WHERE user_id = ? AND ticker = 'AAPL'",
                        Integer.class,
                        userId);
        assertThat(positionCount).isZero();

        // Balance acreditado: 8116.90 + (10 × 189.95 − 37.99) ≈ 8116.90 + 1861.51 = 9978.41
        // Cálculo exacto: subtotal=10×189.9750=1899.75 (precio promedio mid), commission=37.9950 → 38.00 HALF_UP
        // wait — el ask=190.00, bid=189.95, mid=189.975. Subtotal = 10 × 189.9750 = 1899.7500.
        // commission = 1899.75 × 0.02 = 37.9950 → HALF_UP scale=2 = 38.00
        // executionTotal con execPrice=189.95: 10 × 189.95 − 38.00 = 1899.50 − 38.00 = 1861.50
        // newBalance = 8116.90 + 1861.50 = 9978.40
        BigDecimal balance =
                jdbc.queryForObject(
                        "SELECT balance FROM app.user_balances WHERE user_id = ?",
                        BigDecimal.class,
                        userId);
        assertThat(balance).isEqualByComparingTo("9978.40");
    }

    @Test
    void placeOrder_sellShortSelling_returns409_alpacaNeverCalled() throws Exception {
        UUID clientOrderId = UUID.randomUUID();
        stubDataLatestQuote("MSFT", "420.00", "419.50");
        // Stub Alpaca por si acaso (verificaremos que NUNCA se invoca).
        stubTradingSellFilled("alp-unreachable", clientOrderId, 1, "420.00");

        mockMvc.perform(
                        post("/api/v1/orders")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        String.format(
                                                """
                                                {"clientOrderId":"%s","ticker":"MSFT","side":"SELL","type":"MARKET","quantity":1}
                                                """,
                                                clientOrderId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SHORT_SELLING_NOT_ALLOWED"));

        // Alpaca trading NUNCA invocado.
        wm.verify(exactly(0), postRequestedFor(urlEqualTo("/v2/orders")));

        // Posición original intacta.
        Integer positionQty =
                jdbc.queryForObject(
                        "SELECT quantity FROM app.positions WHERE user_id = ? AND ticker = 'AAPL'",
                        Integer.class,
                        userId);
        assertThat(positionQty).isEqualTo(10);
    }

    @Test
    void placeOrder_sellInsufficientShares_returns409_alpacaNeverCalled() throws Exception {
        UUID clientOrderId = UUID.randomUUID();
        stubDataLatestQuote("AAPL", "190.00", "189.95");
        stubTradingSellFilled("alp-unreachable", clientOrderId, 50, "189.95");

        mockMvc.perform(
                        post("/api/v1/orders")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        String.format(
                                                """
                                                {"clientOrderId":"%s","ticker":"AAPL","side":"SELL","type":"MARKET","quantity":50}
                                                """,
                                                clientOrderId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_SHARES"));

        wm.verify(exactly(0), postRequestedFor(urlEqualTo("/v2/orders")));

        Integer positionQty =
                jdbc.queryForObject(
                        "SELECT quantity FROM app.positions WHERE user_id = ? AND ticker = 'AAPL'",
                        Integer.class,
                        userId);
        assertThat(positionQty).isEqualTo(10);
    }

    @Test
    void placeOrder_sellAlpacaRejected_returns422_positionAndBalanceIntact() throws Exception {
        UUID clientOrderId = UUID.randomUUID();
        stubDataLatestQuote("AAPL", "190.00", "189.95");
        stubTradingSellRejected("alp-rej", clientOrderId, "asset not tradeable");

        mockMvc.perform(
                        post("/api/v1/orders")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        String.format(
                                                """
                                                {"clientOrderId":"%s","ticker":"AAPL","side":"SELL","type":"MARKET","quantity":5}
                                                """,
                                                clientOrderId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("ALPACA_ORDER_REJECTED"));

        // Posición y balance intactos.
        Integer positionQty =
                jdbc.queryForObject(
                        "SELECT quantity FROM app.positions WHERE user_id = ? AND ticker = 'AAPL'",
                        Integer.class,
                        userId);
        assertThat(positionQty).isEqualTo(10);
        BigDecimal balance =
                jdbc.queryForObject(
                        "SELECT balance FROM app.user_balances WHERE user_id = ?",
                        BigDecimal.class,
                        userId);
        assertThat(balance).isEqualByComparingTo("8116.90");

        // Order persistida como REJECTED.
        String orderStatus =
                jdbc.queryForObject(
                        "SELECT status FROM app.orders WHERE client_order_id = ?",
                        String.class,
                        clientOrderId);
        assertThat(orderStatus).isEqualTo("REJECTED");
    }

    @Test
    void placeOrder_sellAlpacaDown_returns502_positionAndBalanceIntact() throws Exception {
        UUID clientOrderId = UUID.randomUUID();
        stubDataLatestQuote("AAPL", "190.00", "189.95");
        stubTradingDown();

        mockMvc.perform(
                        post("/api/v1/orders")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        String.format(
                                                """
                                                {"clientOrderId":"%s","ticker":"AAPL","side":"SELL","type":"MARKET","quantity":5}
                                                """,
                                                clientOrderId)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("ALPACA_API_ERROR"));

        // 3 reintentos (max-attempts del retry config).
        wm.verify(exactly(3), postRequestedFor(urlEqualTo("/v2/orders")));

        Integer positionQty =
                jdbc.queryForObject(
                        "SELECT quantity FROM app.positions WHERE user_id = ? AND ticker = 'AAPL'",
                        Integer.class,
                        userId);
        assertThat(positionQty).isEqualTo(10);
        BigDecimal balance =
                jdbc.queryForObject(
                        "SELECT balance FROM app.user_balances WHERE user_id = ?",
                        BigDecimal.class,
                        userId);
        assertThat(balance).isEqualByComparingTo("8116.90");

        String orderStatus =
                jdbc.queryForObject(
                        "SELECT status FROM app.orders WHERE client_order_id = ?",
                        String.class,
                        clientOrderId);
        assertThat(orderStatus).isEqualTo("FAILED");
    }

    @Test
    void placeOrder_sellIdempotency_secondCallReturns200_decrementOnlyOnce() throws Exception {
        UUID clientOrderId = UUID.randomUUID();
        stubDataLatestQuote("AAPL", "190.00", "189.95");
        stubTradingSellFilled("alp-sell-idem", clientOrderId, 5, "189.9500");

        MvcResult first =
                mockMvc.perform(
                                post("/api/v1/orders")
                                        .header("Authorization", "Bearer " + accessToken)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                String.format(
                                                        """
                                                        {"clientOrderId":"%s","ticker":"AAPL","side":"SELL","type":"MARKET","quantity":5}
                                                        """,
                                                        clientOrderId)))
                        .andExpect(status().isCreated())
                        .andReturn();
        String firstId = readJsonPath(first, "id");

        MvcResult second =
                mockMvc.perform(
                                post("/api/v1/orders")
                                        .header("Authorization", "Bearer " + accessToken)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                String.format(
                                                        """
                                                        {"clientOrderId":"%s","ticker":"AAPL","side":"SELL","type":"MARKET","quantity":5}
                                                        """,
                                                        clientOrderId)))
                        .andExpect(status().isOk())
                        .andReturn();
        String secondId = readJsonPath(second, "id");
        assertThat(secondId).isEqualTo(firstId);

        // Alpaca trading invocado UNA sola vez.
        wm.verify(exactly(1), postRequestedFor(urlEqualTo("/v2/orders")));

        // Posición decrementada UNA sola vez: 10 → 5.
        Integer positionQty =
                jdbc.queryForObject(
                        "SELECT quantity FROM app.positions WHERE user_id = ? AND ticker = 'AAPL'",
                        Integer.class,
                        userId);
        assertThat(positionQty).isEqualTo(5);

        // Balance acreditado UNA sola vez: 8116.90 + 930.75 = 9047.65.
        BigDecimal balance =
                jdbc.queryForObject(
                        "SELECT balance FROM app.user_balances WHERE user_id = ?",
                        BigDecimal.class,
                        userId);
        assertThat(balance).isEqualByComparingTo("9047.65");
    }

    private static String readJsonPath(MvcResult result, String field) throws Exception {
        com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(result.getResponse().getContentAsString());
        return node.path(field).asText();
    }
}
