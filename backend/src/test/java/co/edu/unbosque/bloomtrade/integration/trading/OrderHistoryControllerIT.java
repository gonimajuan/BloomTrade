package co.edu.unbosque.bloomtrade.integration.trading;

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
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * IT del bundle HU-F17 (historial de órdenes paginado). Postgres real del docker-compose,
 * sin WireMock (el endpoint no toca Alpaca).
 *
 * <p>Cubre los criterios del SPEC §11:
 * <ul>
 *   <li>AC-01 paginado sin filtros</li>
 *   <li>AC-02 filter ticker</li>
 *   <li>AC-03 filter side (smoke explícito)</li>
 *   <li>AC-04 filter combinado ticker+side</li>
 *   <li>AC-05 cross-user (no leakage)</li>
 *   <li>AC-06 side=FOO → 400</li>
 *   <li>AC-07 size > 100 → 400</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderHistoryControllerIT {

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
        jdbc.execute("TRUNCATE app.orders, app.positions, app.user_balances, app.users CASCADE");
        userId = createUser("history-A");
        accessToken = jwtService.generateAccessToken(userId, "INVESTOR");
    }

    private UUID createUser(String prefix) {
        User user =
                User.register(
                        prefix + "-" + UUID.randomUUID() + "@test.local",
                        "$2a$12$dummyHashForTestingOnlyNotARealBcryptHashAtAllxx",
                        prefix + " IT",
                        DocumentType.CC,
                        "8300" + System.nanoTime() % 1_000_000,
                        "+573009998877",
                        Instant.now());
        user = userRepository.save(user);
        balanceInitializer.initializeBalance(user.getId());
        return user.getId();
    }

    private void seedOrder(
            UUID uid, String ticker, String side, int qty, String status, Instant submittedAt) {
        jdbc.update(
                "INSERT INTO app.orders (id, user_id, client_order_id, ticker, side, type,"
                        + " quantity, quoted_unit_price, quoted_commission, quoted_total,"
                        + " status, submitted_at) VALUES (?, ?, ?, ?, ?, 'MARKET', ?,"
                        + " 100.0000, 2.0000, ?, ?, ?)",
                UUID.randomUUID(),
                uid,
                UUID.randomUUID(),
                ticker,
                side,
                qty,
                new BigDecimal("100.00").multiply(BigDecimal.valueOf(qty)).add(new BigDecimal("2.00")),
                status,
                Timestamp.from(submittedAt));
    }

    @Test
    void list_noFilters_returnsPaginatedDesc() throws Exception {
        // AC-01: 25 órdenes seedeadas, page 0 size 10 → totalElements 25, returned 10.
        Instant base = Instant.parse("2026-05-01T10:00:00Z");
        for (int i = 0; i < 25; i++) {
            seedOrder(
                    userId,
                    i % 2 == 0 ? "AAPL" : "MSFT",
                    i % 3 == 0 ? "SELL" : "BUY",
                    1 + (i % 5),
                    "PENDING",
                    base.plusSeconds(i * 60L)); // i=24 es más reciente
        }

        mockMvc.perform(
                        get("/api/v1/orders?page=0&size=10")
                                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", Matchers.hasSize(10)))
                .andExpect(jsonPath("$.pagination.page").value(0))
                .andExpect(jsonPath("$.pagination.size").value(10))
                .andExpect(jsonPath("$.pagination.totalElements").value(25))
                .andExpect(jsonPath("$.pagination.totalPages").value(3))
                // Sort DESC: la primera del listado es la última seeded (i=24).
                .andExpect(jsonPath("$.content[0].quantity").value(1 + (24 % 5)));
    }

    @Test
    void list_filterTicker_returnsOnlyMatchingTicker() throws Exception {
        Instant base = Instant.parse("2026-05-01T10:00:00Z");
        // 3 AAPL + 2 MSFT + 1 TSLA.
        seedOrder(userId, "AAPL", "BUY", 5, "EXECUTED", base);
        seedOrder(userId, "AAPL", "SELL", 3, "EXECUTED", base.plusSeconds(60));
        seedOrder(userId, "AAPL", "BUY", 2, "PENDING", base.plusSeconds(120));
        seedOrder(userId, "MSFT", "BUY", 1, "EXECUTED", base.plusSeconds(180));
        seedOrder(userId, "MSFT", "SELL", 1, "EXECUTED", base.plusSeconds(240));
        seedOrder(userId, "TSLA", "BUY", 2, "EXECUTED", base.plusSeconds(300));

        mockMvc.perform(
                        get("/api/v1/orders?ticker=AAPL")
                                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", Matchers.hasSize(3)))
                .andExpect(jsonPath("$.content[*].ticker", Matchers.everyItem(Matchers.is("AAPL"))))
                .andExpect(jsonPath("$.pagination.totalElements").value(3));
    }

    @Test
    void list_filterSide_returnsOnlyMatchingSide() throws Exception {
        Instant base = Instant.parse("2026-05-01T10:00:00Z");
        // 3 BUY + 2 SELL.
        seedOrder(userId, "AAPL", "BUY", 5, "EXECUTED", base);
        seedOrder(userId, "MSFT", "BUY", 1, "EXECUTED", base.plusSeconds(60));
        seedOrder(userId, "TSLA", "BUY", 2, "EXECUTED", base.plusSeconds(120));
        seedOrder(userId, "AAPL", "SELL", 3, "EXECUTED", base.plusSeconds(180));
        seedOrder(userId, "MSFT", "SELL", 1, "EXECUTED", base.plusSeconds(240));

        mockMvc.perform(
                        get("/api/v1/orders?side=BUY")
                                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", Matchers.hasSize(3)))
                .andExpect(jsonPath("$.content[*].side", Matchers.everyItem(Matchers.is("BUY"))));
    }

    @Test
    void list_filterTickerAndSide_returnsOnlyBoth() throws Exception {
        Instant base = Instant.parse("2026-05-01T10:00:00Z");
        seedOrder(userId, "AAPL", "BUY", 5, "EXECUTED", base);
        seedOrder(userId, "AAPL", "BUY", 3, "EXECUTED", base.plusSeconds(60));
        seedOrder(userId, "AAPL", "SELL", 2, "EXECUTED", base.plusSeconds(120));
        seedOrder(userId, "MSFT", "BUY", 1, "EXECUTED", base.plusSeconds(180));

        mockMvc.perform(
                        get("/api/v1/orders?ticker=AAPL&side=BUY")
                                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", Matchers.hasSize(2)))
                .andExpect(jsonPath("$.content[*].ticker", Matchers.everyItem(Matchers.is("AAPL"))))
                .andExpect(jsonPath("$.content[*].side", Matchers.everyItem(Matchers.is("BUY"))));
    }

    @Test
    void list_isolatedPerUser_userBOrdersNotVisibleToA() throws Exception {
        UUID userB = createUser("history-B");
        Instant base = Instant.parse("2026-05-01T10:00:00Z");
        seedOrder(userId, "AAPL", "BUY", 5, "EXECUTED", base);
        seedOrder(userId, "MSFT", "BUY", 3, "EXECUTED", base.plusSeconds(60));
        seedOrder(userB, "TSLA", "BUY", 10, "EXECUTED", base.plusSeconds(120));
        seedOrder(userB, "NVDA", "BUY", 5, "EXECUTED", base.plusSeconds(180));

        mockMvc.perform(
                        get("/api/v1/orders")
                                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", Matchers.hasSize(2)))
                .andExpect(
                        jsonPath(
                                "$.content[*].ticker",
                                Matchers.everyItem(Matchers.oneOf("AAPL", "MSFT"))));
    }

    @Test
    void list_invalidSideParam_returns400() throws Exception {
        mockMvc.perform(
                        get("/api/v1/orders?side=FOO")
                                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST_PARAMETER"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("side"));
    }

    @Test
    void list_sizeAboveCap_returns400() throws Exception {
        mockMvc.perform(
                        get("/api/v1/orders?size=200")
                                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST_PARAMETER"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("size"));
    }
}
