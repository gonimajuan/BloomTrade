package co.edu.unbosque.bloomtrade.integration.dashboard;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * IT del bundle HU-F18 vía {@code GET /api/v1/dashboard/snapshot}. Postgres + Redis del
 * docker-compose + WireMock para Alpaca data API (quotes + bars). Limpia cache Redis
 * entre tests para aislamiento (D15 D-REDIS-TEST-PROFILE).
 *
 * <p>Cubre HU-F18 SPEC §11 AC-01, AC-02 (cache hit), AC-03 (Alpaca down), AC-04 (sin posiciones).
 * Tests adicionales en Lote E (cross-user, TTL expiración, Redis down).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardControllerIT {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    @DynamicPropertySource
    static void registerWireMockUrl(DynamicPropertyRegistry registry) {
        registry.add("alpaca.data-base-url", wm::baseUrl);
        registry.add("alpaca.trading-base-url", wm::baseUrl);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
    @Autowired private BalanceInitializer balanceInitializer;
    @Autowired private RedisTemplate<String, String> redisTemplate;
    @MockBean private Notifier notifier;
    @MockBean private Auditor auditor;

    private UUID userId;
    private String accessToken;

    @BeforeEach
    void setup() {
        wm.resetAll();
        jdbc.execute("TRUNCATE app.orders, app.positions, app.user_balances, app.users CASCADE");
        clearCache();

        User user =
                User.register(
                        "dash-" + UUID.randomUUID() + "@test.local",
                        "$2a$12$dummyHashForTestingOnlyNotARealBcryptHashAtAllxx",
                        "Dashboard IT",
                        DocumentType.CC,
                        "9200" + System.nanoTime() % 1_000_000,
                        "+573006669999",
                        Instant.now());
        user = userRepository.save(user);
        userId = user.getId();
        balanceInitializer.initializeBalance(userId);
        accessToken = jwtService.generateAccessToken(userId, "INVESTOR");
    }

    @AfterEach
    void cleanCache() {
        clearCache();
    }

    private void clearCache() {
        Set<String> keys = redisTemplate.keys("market-data:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    // ─── stubs catch-all WireMock ─────────────────────────────────────────────

    /** Stub para TODOS los tickers en /quotes/latest devolviendo ask=bid=100 → mid=100. */
    private void stubAllQuotesOk() {
        wm.stubFor(
                WireMock.get(urlPathMatching("/v2/stocks/[A-Z0-9]+/quotes/latest"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"symbol\":\"X\",\"quote\":{\"ap\":100.00,\"bp\":100.00,\"as\":100,\"bs\":100,\"t\":\"2026-05-25T14:00:00Z\"}}")));
    }

    /** Stub para TODOS los /bars devolviendo 2 barras simples. */
    private void stubAllBarsOk() {
        wm.stubFor(
                WireMock.get(urlPathMatching("/v2/stocks/[A-Z0-9]+/bars"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"bars\":{\"X\":[{\"t\":\"2026-05-25T13:30:00Z\",\"o\":99.00,\"h\":100.50,\"l\":98.80,\"c\":100.00,\"v\":1000}]},\"next_page_token\":null}")));
    }

    /** Stub que devuelve 503 a todas las llamadas a /quotes/latest. */
    private void stubAllQuotesDown() {
        wm.stubFor(
                WireMock.get(urlPathMatching("/v2/stocks/[A-Z0-9]+/quotes/latest"))
                        .willReturn(aResponse().withStatus(503).withBody("{\"message\":\"down\"}")));
    }

    /** Stub que devuelve 503 a todas las llamadas a /bars. */
    private void stubAllBarsDown() {
        wm.stubFor(
                WireMock.get(urlPathMatching("/v2/stocks/[A-Z0-9]+/bars"))
                        .willReturn(aResponse().withStatus(503).withBody("{\"message\":\"down\"}")));
    }

    private void seedPosition(String ticker, int quantity, String avgBuyPrice) {
        jdbc.update(
                "INSERT INTO app.positions (id, user_id, ticker, quantity, avg_buy_price) VALUES (?, ?, ?, ?, ?)",
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

    // ─── Tests ────────────────────────────────────────────────────────────────

    @Test
    void snapshot_happyCacheMiss_returnsPopulatedWithMarketDataTrue() throws Exception {
        // AC-01: usuario con posiciones + Alpaca responde todo → equity + sparklines + true.
        overrideBalance(new BigDecimal("5234.45"));
        seedPosition("AAPL", 10, "90.00"); // cost basis 900, mkt value 1000 → pnl +100
        stubAllQuotesOk();
        stubAllBarsOk();

        mockMvc.perform(
                        get("/api/v1/dashboard/snapshot")
                                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marketDataAvailable").value("true"))
                .andExpect(jsonPath("$.tickers", org.hamcrest.Matchers.hasSize(5)))
                .andExpect(jsonPath("$.tickers[0].market").value("NYSE"))
                .andExpect(jsonPath("$.tickers[1].market").value("NASDAQ"))
                .andExpect(jsonPath("$.tickers[0].items", org.hamcrest.Matchers.hasSize(5)))
                .andExpect(jsonPath("$.equity.balance").value("5234.45"))
                // mkt value AAPL = 10 × 100.00 = 1000.00
                .andExpect(jsonPath("$.equity.positionsMarketValue").value("1000.00"))
                // equity = 5234.45 + 1000 = 6234.45
                .andExpect(jsonPath("$.equity.equity").value("6234.45"))
                .andExpect(jsonPath("$.equity.costBasisTotal").value("900.00"))
                .andExpect(jsonPath("$.equity.unrealizedPnL").value("100.00"));

        // Verifica que se llenó el cache Redis (25 keys de precio).
        Set<String> keys = redisTemplate.keys("market-data:price:*");
        assertThat(keys).isNotNull().hasSize(25);
    }

    @Test
    void snapshot_secondCallWithinTtl_servesPricesFromCache() throws Exception {
        // AC-02: primer request popula cache, segundo no llama Alpaca para /quotes (bars sí, V1 sin cache).
        overrideBalance(new BigDecimal("5000.00"));
        stubAllQuotesOk();
        stubAllBarsOk();

        // Primera llamada: cache miss → cache populated.
        mockMvc.perform(
                        get("/api/v1/dashboard/snapshot")
                                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        // Reset request counter para que las verifies cuenten solo desde aquí.
        wm.resetRequests();

        // Segunda llamada: cache hit total.
        mockMvc.perform(
                        get("/api/v1/dashboard/snapshot")
                                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marketDataAvailable").value("true"));

        // Plan D5: bars NO se cachean en V1 (cada poll re-fetches). Pero quotes/latest SÍ debería tener 0 calls.
        wm.verify(0, getRequestedFor(urlPathMatching("/v2/stocks/[A-Z0-9]+/quotes/latest")));
    }

    @Test
    void snapshot_alpacaDown_marketDataAvailableFalse() throws Exception {
        // AC-03: Alpaca caído → 200 OK + marketDataAvailable="false" + equity sin marketValue.
        overrideBalance(new BigDecimal("5000.00"));
        seedPosition("AAPL", 10, "90.00");
        stubAllQuotesDown();
        stubAllBarsDown();

        mockMvc.perform(
                        get("/api/v1/dashboard/snapshot")
                                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marketDataAvailable").value("false"))
                .andExpect(jsonPath("$.tickers[0].items[0].currentPrice").doesNotExist())
                .andExpect(jsonPath("$.equity.balance").value("5000.00"))
                .andExpect(jsonPath("$.equity.positionsMarketValue").doesNotExist())
                .andExpect(jsonPath("$.equity.equity").doesNotExist())
                .andExpect(jsonPath("$.equity.costBasisTotal").value("900.00"));

        // Redis NO debería contaminarse con nulls (plan D16).
        Set<String> keys = redisTemplate.keys("market-data:price:*");
        assertThat(keys == null || keys.isEmpty()).isTrue();
    }

    @Test
    void snapshot_userWithoutPositions_equityFromBalanceOnly() throws Exception {
        // AC-04: usuario nuevo sin posiciones → equity=balance, pnlPct=null.
        // Balance ya es 10000 por bootstrap; sin overrideBalance.
        stubAllQuotesOk();
        stubAllBarsOk();

        mockMvc.perform(
                        get("/api/v1/dashboard/snapshot")
                                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.equity.balance").value("10000.00"))
                .andExpect(jsonPath("$.equity.positionsMarketValue").value("0.00"))
                .andExpect(jsonPath("$.equity.equity").value("10000.00"))
                .andExpect(jsonPath("$.equity.costBasisTotal").value("0.00"))
                .andExpect(jsonPath("$.equity.unrealizedPnL").value("0.00"))
                // pct null cuando costBasis=0 (evita div por cero).
                .andExpect(jsonPath("$.equity.unrealizedPnLPct").doesNotExist())
                .andExpect(jsonPath("$.marketDataAvailable").value("true"));
    }

    // ─── Lote E IT adicionales ────────────────────────────────────────────────

    @Test
    void snapshot_isolatedPerUser_equityNotLeakedAcrossUsers() throws Exception {
        // T5.1: usuario A con 10 AAPL @90, usuario B con 20 MSFT @50. A llama /snapshot →
        // equity refleja solo AAPL (NO MSFT de B). El JWT actual es de A.
        overrideBalance(new BigDecimal("5000.00"));
        seedPosition("AAPL", 10, "90.00"); // costBasis 900 para A
        // Crear B y sus posiciones.
        User userB =
                User.register(
                        "dash-B-" + UUID.randomUUID() + "@test.local",
                        "$2a$12$dummyHashForTestingOnlyNotARealBcryptHashAtAllxx",
                        "Dashboard IT B",
                        DocumentType.CC,
                        "9300" + System.nanoTime() % 1_000_000,
                        "+573006661111",
                        Instant.now());
        userB = userRepository.save(userB);
        balanceInitializer.initializeBalance(userB.getId());
        jdbc.update(
                "INSERT INTO app.positions (id, user_id, ticker, quantity, avg_buy_price) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                userB.getId(),
                "MSFT",
                20,
                new BigDecimal("50.00")); // costBasis 1000 para B (no debe aparecer en A)
        stubAllQuotesOk();
        stubAllBarsOk();

        mockMvc.perform(
                        get("/api/v1/dashboard/snapshot")
                                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                // A: marketValue = 10 × 100 = 1000 (NO 10×100 + 20×100 = 3000 si leakeara).
                .andExpect(jsonPath("$.equity.positionsMarketValue").value("1000.00"))
                .andExpect(jsonPath("$.equity.costBasisTotal").value("900.00"));
    }

    @Test
    void snapshot_withoutJwt_returns403_dueToCrossCuttingDeudaD17F16() throws Exception {
        // T5.2: SPEC §11 AC-07 declara 401 pero el JwtAuthenticationFilter del proyecto
        // solo emite 401 con token inválido/expirado; sin header Spring Security 6 cae en
        // 403 por default (no hay AuthenticationEntryPoint custom). Mismo patrón que F16+F21
        // PortfolioControllerIT D17 emergente. Fix real diferido a mini-HU token-rotation-logout.
        mockMvc.perform(get("/api/v1/dashboard/snapshot"))
                .andExpect(status().isForbidden());
    }

    @Test
    void snapshot_cacheTtlExpiresManually_secondCallHitsAlpacaAgain() throws Exception {
        // T5.3: primer request popula 25 keys con TTL 30s. Forzamos expiración inmediata
        // con `expire(key, 0s)` para los 25 keys → segunda call debe golpear Alpaca otra vez.
        stubAllQuotesOk();
        stubAllBarsOk();

        // Primera llamada: cache miss → llena 25 keys.
        mockMvc.perform(
                        get("/api/v1/dashboard/snapshot")
                                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
        Set<String> keys = redisTemplate.keys("market-data:price:*");
        assertThat(keys).isNotNull().hasSize(25);

        // Expire manual: borrar todas las keys de price (equivalente a TTL=0).
        redisTemplate.delete(keys);
        wm.resetRequests();

        // Segunda llamada: cache vacío → quotes/latest se re-invoca para los 25 tickers.
        mockMvc.perform(
                        get("/api/v1/dashboard/snapshot")
                                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marketDataAvailable").value("true"));

        // Verifica que WireMock recibió ≥1 request a quotes/latest tras el reset (cache miss
        // forzado por la expiración manual). Usamos findAll para evitar matchers numéricos.
        int callsAfterExpire =
                wm.findAll(
                                com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(
                                        urlPathMatching("/v2/stocks/[A-Z0-9]+/quotes/latest")))
                        .size();
        assertThat(callsAfterExpire).isGreaterThanOrEqualTo(1);
    }
}
