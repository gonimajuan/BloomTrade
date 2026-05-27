package co.edu.unbosque.bloomtrade.integration.trading;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
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
import co.edu.unbosque.bloomtrade.portfolio.service.BalanceInitializer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.math.BigDecimal;
import java.sql.Timestamp;
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
 * IT del endpoint {@code POST /api/v1/orders/{id}/cancel} (HU-F15 Lote C T3.5). Postgres real
 * (bloomtrade_test) + WireMock para Alpaca trading API.
 *
 * <p>Cubre los criterios de aceptación del SPEC §11.1: E1 (cancel BUY refund), E2 (cancel SELL
 * restore re-INSERT), E3 (polling-timeout cancelRequestedAt), E6 (409 NOT_CANCELABLE), E7 (404
 * anti-enumeración), E8 (502 broker down), idempotency.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TradingControllerCancelIT {

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
                        "trader-cancel-" + UUID.randomUUID() + "@test.local",
                        "$2a$12$dummyHashForTestingOnlyNotARealBcryptHashAtAllxx",
                        "Trader Cancel IT",
                        DocumentType.CC,
                        "9200" + System.nanoTime() % 1_000_000,
                        "+573009998877",
                        Instant.now());
        user = userRepository.save(user);
        userId = user.getId();
        balanceInitializer.initializeBalance(userId);
        accessToken = jwtService.generateAccessToken(userId, "INVESTOR");
    }

    private UUID seedPendingBuyOrder(String alpacaOrderId, BigDecimal quotedTotal) {
        UUID orderId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO app.orders (id, user_id, client_order_id, ticker, side, type,"
                        + " quantity, quoted_unit_price, quoted_commission, quoted_total,"
                        + " status, alpaca_order_id, submitted_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                orderId,
                userId,
                UUID.randomUUID(),
                "AAPL",
                "BUY",
                "MARKET",
                5,
                new BigDecimal("200.0000"),
                new BigDecimal("20.00"),
                quotedTotal,
                "PENDING",
                alpacaOrderId,
                Timestamp.from(Instant.now()));
        return orderId;
    }

    private UUID seedPendingSellOrderWithSnapshot(String alpacaOrderId) {
        UUID orderId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO app.orders (id, user_id, client_order_id, ticker, side, type,"
                        + " quantity, quoted_unit_price, quoted_commission, quoted_total,"
                        + " status, alpaca_order_id, avg_buy_price_at_submission, submitted_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                orderId,
                userId,
                UUID.randomUUID(),
                "AAPL",
                "SELL",
                "MARKET",
                5,
                new BigDecimal("200.0000"),
                new BigDecimal("20.00"),
                new BigDecimal("980.00"),
                "PENDING",
                alpacaOrderId,
                new BigDecimal("184.6234"),
                Timestamp.from(Instant.now()));
        return orderId;
    }

    private void debitBalance(BigDecimal amount) {
        jdbc.update(
                "UPDATE app.user_balances SET balance = balance - ? WHERE user_id = ?",
                amount,
                userId);
    }

    private BigDecimal currentBalance() {
        return jdbc.queryForObject(
                "SELECT balance FROM app.user_balances WHERE user_id = ?",
                BigDecimal.class,
                userId);
    }

    private String orderStatus(UUID orderId) {
        return jdbc.queryForObject(
                "SELECT status FROM app.orders WHERE id = ?", String.class, orderId);
    }

    private void stubAlpacaCancelOK(String alpacaOrderId) {
        wm.stubFor(
                delete(urlPathEqualTo("/v2/orders/" + alpacaOrderId))
                        .willReturn(aResponse().withStatus(204)));
        wm.stubFor(
                get(urlPathEqualTo("/v2/orders/" + alpacaOrderId))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"id\":\""
                                                        + alpacaOrderId
                                                        + "\",\"status\":\"canceled\",\"canceled_at\":\"2026-05-27T15:42:25Z\"}")));
    }

    private void stubAlpacaCancelPollingTimeout(String alpacaOrderId) {
        wm.stubFor(
                delete(urlPathEqualTo("/v2/orders/" + alpacaOrderId))
                        .willReturn(aResponse().withStatus(204)));
        wm.stubFor(
                get(urlPathEqualTo("/v2/orders/" + alpacaOrderId))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"id\":\""
                                                        + alpacaOrderId
                                                        + "\",\"status\":\"accepted\"}")));
    }

    private void stubAlpacaCancelDown(String alpacaOrderId) {
        wm.stubFor(
                delete(urlPathEqualTo("/v2/orders/" + alpacaOrderId))
                        .willReturn(aResponse().withStatus(503).withBody("{\"message\":\"down\"}")));
    }

    @Test
    void cancelBuy_pollingOK_returns200WithCanceledAndRefundsBalance() throws Exception {
        String alpacaId = "alp-buy-happy-" + System.nanoTime();
        UUID orderId = seedPendingBuyOrder(alpacaId, new BigDecimal("1020.00"));
        debitBalance(new BigDecimal("1020.00"));
        BigDecimal balanceBefore = currentBalance();
        stubAlpacaCancelOK(alpacaId);

        mockMvc.perform(
                        post("/api/v1/orders/{id}/cancel", orderId)
                                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.refundedAmount").value("1020.00"))
                .andExpect(jsonPath("$.canceledAt").exists());

        assertThat(orderStatus(orderId)).isEqualTo("CANCELED");
        assertThat(currentBalance())
                .isEqualByComparingTo(balanceBefore.add(new BigDecimal("1020.00")));
    }

    @Test
    void cancelSell_pollingOK_returns200WithRestoredQty_reInsertPosition() throws Exception {
        String alpacaId = "alp-sell-happy-" + System.nanoTime();
        UUID orderId = seedPendingSellOrderWithSnapshot(alpacaId);
        stubAlpacaCancelOK(alpacaId);

        mockMvc.perform(
                        post("/api/v1/orders/{id}/cancel", orderId)
                                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.restoredQty").value(5));

        Integer posQty =
                jdbc.queryForObject(
                        "SELECT quantity FROM app.positions WHERE user_id = ? AND ticker = ?",
                        Integer.class,
                        userId,
                        "AAPL");
        assertThat(posQty).isEqualTo(5);
        BigDecimal avgBuy =
                jdbc.queryForObject(
                        "SELECT avg_buy_price FROM app.positions WHERE user_id = ? AND ticker = ?",
                        BigDecimal.class,
                        userId,
                        "AAPL");
        assertThat(avgBuy).isEqualByComparingTo(new BigDecimal("184.6234"));
    }

    @Test
    void cancel_pollingTimeout_returns200WithPendingAndCancelRequestedAt() throws Exception {
        String alpacaId = "alp-timeout-" + System.nanoTime();
        UUID orderId = seedPendingBuyOrder(alpacaId, new BigDecimal("1020.00"));
        debitBalance(new BigDecimal("1020.00"));
        BigDecimal balanceBefore = currentBalance();
        stubAlpacaCancelPollingTimeout(alpacaId);

        mockMvc.perform(
                        post("/api/v1/orders/{id}/cancel", orderId)
                                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.cancelRequestedAt").exists());

        assertThat(orderStatus(orderId)).isEqualTo("PENDING");
        Timestamp cancelReqAt =
                jdbc.queryForObject(
                        "SELECT cancel_requested_at FROM app.orders WHERE id = ?",
                        Timestamp.class,
                        orderId);
        assertThat(cancelReqAt).isNotNull();
        assertThat(currentBalance()).isEqualByComparingTo(balanceBefore);
    }

    @Test
    void cancel_crossUser_returns404_OrderNotFound() throws Exception {
        String alpacaId = "alp-cross-" + System.nanoTime();
        UUID orderId = seedPendingBuyOrder(alpacaId, new BigDecimal("1020.00"));
        User otherUser =
                User.register(
                        "other-" + UUID.randomUUID() + "@test.local",
                        "$2a$12$dummyHashForTestingOnlyNotARealBcryptHashAtAllxx",
                        "Other User",
                        DocumentType.CC,
                        "9300" + System.nanoTime() % 1_000_000,
                        "+573000000000",
                        Instant.now());
        otherUser = userRepository.save(otherUser);
        balanceInitializer.initializeBalance(otherUser.getId());
        String otherToken = jwtService.generateAccessToken(otherUser.getId(), "INVESTOR");

        mockMvc.perform(
                        post("/api/v1/orders/{id}/cancel", orderId)
                                .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ORDER_NOT_FOUND"));

        wm.verify(0, WireMock.deleteRequestedFor(urlPathEqualTo("/v2/orders/" + alpacaId)));
        assertThat(orderStatus(orderId)).isEqualTo("PENDING");
    }

    @Test
    void cancel_orderExecuted_returns409_OrderNotCancelable() throws Exception {
        UUID orderId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO app.orders (id, user_id, client_order_id, ticker, side, type,"
                        + " quantity, quoted_unit_price, quoted_commission, quoted_total,"
                        + " status, alpaca_order_id, executed_at, submitted_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                orderId,
                userId,
                UUID.randomUUID(),
                "AAPL",
                "BUY",
                "MARKET",
                5,
                new BigDecimal("200.0000"),
                new BigDecimal("20.00"),
                new BigDecimal("1020.00"),
                "EXECUTED",
                "alp-exec-" + System.nanoTime(),
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()));

        mockMvc.perform(
                        post("/api/v1/orders/{id}/cancel", orderId)
                                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ORDER_NOT_CANCELABLE"));
    }

    @Test
    void cancel_alpacaDownPostRetries_returns502_orderUntouched() throws Exception {
        String alpacaId = "alp-down-" + System.nanoTime();
        UUID orderId = seedPendingBuyOrder(alpacaId, new BigDecimal("1020.00"));
        debitBalance(new BigDecimal("1020.00"));
        BigDecimal balanceBefore = currentBalance();
        stubAlpacaCancelDown(alpacaId);

        mockMvc.perform(
                        post("/api/v1/orders/{id}/cancel", orderId)
                                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("ALPACA_API_ERROR"));

        assertThat(orderStatus(orderId)).isEqualTo("PENDING");
        Timestamp cancelReqAt =
                jdbc.queryForObject(
                        "SELECT cancel_requested_at FROM app.orders WHERE id = ?",
                        Timestamp.class,
                        orderId);
        assertThat(cancelReqAt).isNull();
        assertThat(currentBalance()).isEqualByComparingTo(balanceBefore);
    }

    @Test
    void cancel_idempotentSecondCallOnCanceled_returns200NoSecondAlpacaCall() throws Exception {
        String alpacaId = "alp-idem-" + System.nanoTime();
        UUID orderId = seedPendingBuyOrder(alpacaId, new BigDecimal("1020.00"));
        debitBalance(new BigDecimal("1020.00"));
        stubAlpacaCancelOK(alpacaId);

        mockMvc.perform(
                        post("/api/v1/orders/{id}/cancel", orderId)
                                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));

        mockMvc.perform(
                        post("/api/v1/orders/{id}/cancel", orderId)
                                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));

        wm.verify(1, WireMock.deleteRequestedFor(urlPathEqualTo("/v2/orders/" + alpacaId)));
    }

    // ─── Lote E — IT adicionales ──────────────────────────────────────────────

    @Test
    void cancel_idempotentPollingTimeout_secondCallNoSecondDelete() throws Exception {
        // T5.1.a: re-request sobre orden ya en cancel_requested_at no llama Alpaca de nuevo.
        String alpacaId = "alp-idem-timeout-" + System.nanoTime();
        UUID orderId = seedPendingBuyOrder(alpacaId, new BigDecimal("1020.00"));
        debitBalance(new BigDecimal("1020.00"));
        stubAlpacaCancelPollingTimeout(alpacaId);

        // 1er cancel → polling-timeout, queda PENDING + cancel_requested_at.
        mockMvc.perform(
                        post("/api/v1/orders/{id}/cancel", orderId)
                                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.cancelRequestedAt").exists());

        // 2do cancel inmediato → idempotent short-circuit, mismo body, NO segundo DELETE.
        mockMvc.perform(
                        post("/api/v1/orders/{id}/cancel", orderId)
                                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.cancelRequestedAt").exists());

        // Verificación clave: Alpaca DELETE recibió exactamente 1 request (no 2).
        wm.verify(1, WireMock.deleteRequestedFor(urlPathEqualTo("/v2/orders/" + alpacaId)));
    }

    @Test
    void cancel_raceWithReconcileExecuted_returns409NotCancelable() throws Exception {
        // T5.1.b: simulamos race con reconcile materializando EXECUTED — se hace seteando el
        // status directamente en BD (en práctica un GET /portfolio del usuario en otra pestaña
        // que activó reconcileOne PENDING→EXECUTED). El cancel posterior debe ver el estado
        // terminal y devolver 409.
        String alpacaId = "alp-race-" + System.nanoTime();
        UUID orderId = seedPendingBuyOrder(alpacaId, new BigDecimal("1020.00"));
        // Simular reconcile que materializó la orden ANTES del cancel.
        jdbc.update(
                "UPDATE app.orders SET status = 'EXECUTED', execution_unit_price = ?,"
                        + " execution_total = ?, executed_at = ? WHERE id = ?",
                new BigDecimal("198.5000"),
                new BigDecimal("1012.50"),
                Timestamp.from(Instant.now()),
                orderId);

        mockMvc.perform(
                        post("/api/v1/orders/{id}/cancel", orderId)
                                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ORDER_NOT_CANCELABLE"));

        // NO se llamó Alpaca (short-circuit por status terminal).
        wm.verify(0, WireMock.deleteRequestedFor(urlPathEqualTo("/v2/orders/" + alpacaId)));
        // Orden sigue EXECUTED intacta.
        assertThat(orderStatus(orderId)).isEqualTo("EXECUTED");
    }
}
