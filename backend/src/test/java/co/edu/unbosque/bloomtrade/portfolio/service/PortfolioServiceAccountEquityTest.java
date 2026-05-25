package co.edu.unbosque.bloomtrade.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.unbosque.bloomtrade.auth.domain.DocumentType;
import co.edu.unbosque.bloomtrade.auth.domain.User;
import co.edu.unbosque.bloomtrade.auth.repository.UserRepository;
import co.edu.unbosque.bloomtrade.dashboard.dto.AccountEquityDto;
import co.edu.unbosque.bloomtrade.portfolio.domain.Position;
import co.edu.unbosque.bloomtrade.portfolio.repository.PositionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * IT del nuevo método {@link PortfolioService#getAccountEquity} (HU-F18 Lote B).
 * Sigue el patrón de {@code PortfolioServiceTest} (Postgres real {@code bloomtrade_test}).
 *
 * <p>Cubre los 5 escenarios principales de plan D9 + D-EQUITY-PARTIAL:
 * <ul>
 *   <li>Happy path: posiciones con todos los precios — equity, pnl, pct calculados.</li>
 *   <li>Sin posiciones: equity=balance, costBasis=0, pnl=0, pct=null.</li>
 *   <li>Todos los prices null: marketValue/equity/pnl/pct null, costBasis presente.</li>
 *   <li>Prices parciales: sum solo de las que tienen precio.</li>
 *   <li>Cost basis = 0 edge: pct=null para evitar div por cero.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class PortfolioServiceAccountEquityTest {

    @Autowired private PortfolioService portfolioService;
    @Autowired private UserRepository userRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private BalanceInitializer balanceInitializer;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID userId;

    @BeforeEach
    void cleanAndCreateUser() {
        jdbcTemplate.execute(
                "TRUNCATE app.orders, app.positions, app.user_balances, app.users CASCADE");
        User user =
                User.register(
                        "equity-" + UUID.randomUUID() + "@test.local",
                        "$2a$12$dummyHashForTestingOnlyNotARealBcryptHashAtAllxx",
                        "Equity Test",
                        DocumentType.CC,
                        "2000" + System.nanoTime() % 1_000_000,
                        "+573001112244",
                        Instant.now());
        user = userRepository.save(user);
        userId = user.getId();
        balanceInitializer.initializeBalance(userId);
    }

    private void overrideBalance(BigDecimal newBalance) {
        jdbcTemplate.update(
                "UPDATE app.user_balances SET balance = ? WHERE user_id = ?", newBalance, userId);
    }

    private void seedPosition(String ticker, int qty, String avgPrice) {
        positionRepository.save(Position.newPosition(userId, ticker, qty, new BigDecimal(avgPrice)));
    }

    @Test
    void getAccountEquity_happyPath_calculatesAllFields() {
        overrideBalance(new BigDecimal("5234.45"));
        seedPosition("AAPL", 10, "189.45"); // costBasis 1894.50, marketValue (10*193.20)=1932
        seedPosition("MSFT", 5, "412.00");  // costBasis 2060.00, marketValue (5*408.50)=2042.50
        Map<String, BigDecimal> prices = new HashMap<>();
        prices.put("AAPL", new BigDecimal("193.20"));
        prices.put("MSFT", new BigDecimal("408.50"));

        AccountEquityDto equity = portfolioService.getAccountEquity(userId, prices);

        assertThat(equity.balance()).isEqualTo("5234.45");
        assertThat(equity.positionsMarketValue()).isEqualTo("3974.50");
        // 5234.45 + 3974.50 = 9208.95
        assertThat(equity.equity()).isEqualTo("9208.95");
        // costBasis 1894.50 + 2060.00 = 3954.50
        assertThat(equity.costBasisTotal()).isEqualTo("3954.50");
        // pnl = 3974.50 − 3954.50 = 20.00
        assertThat(equity.unrealizedPnL()).isEqualTo("20.00");
        // pct = 20.00 / 3954.50 × 100 ≈ 0.51
        assertThat(equity.unrealizedPnLPct()).isEqualTo("0.51");
        assertThat(equity.currency()).isEqualTo("USD");
    }

    @Test
    void getAccountEquity_noPositions_equityEqualsBalance_pctIsNull() {
        overrideBalance(new BigDecimal("10000.00"));

        AccountEquityDto equity = portfolioService.getAccountEquity(userId, Map.of());

        assertThat(equity.balance()).isEqualTo("10000.00");
        assertThat(equity.positionsMarketValue()).isEqualTo("0.00");
        assertThat(equity.equity()).isEqualTo("10000.00");
        assertThat(equity.costBasisTotal()).isEqualTo("0.00");
        assertThat(equity.unrealizedPnL()).isEqualTo("0.00");
        assertThat(equity.unrealizedPnLPct()).isNull();
    }

    @Test
    void getAccountEquity_pricesAllNull_marketValueAndPnLNull_costBasisPresent() {
        overrideBalance(new BigDecimal("5000.00"));
        seedPosition("AAPL", 10, "189.45");
        Map<String, BigDecimal> prices = new HashMap<>();
        prices.put("AAPL", null);

        AccountEquityDto equity = portfolioService.getAccountEquity(userId, prices);

        assertThat(equity.balance()).isEqualTo("5000.00");
        assertThat(equity.positionsMarketValue()).isNull();
        assertThat(equity.equity()).isNull();
        assertThat(equity.costBasisTotal()).isEqualTo("1894.50");
        assertThat(equity.unrealizedPnL()).isNull();
        assertThat(equity.unrealizedPnLPct()).isNull();
    }

    @Test
    void getAccountEquity_pricesPartial_sumsOnlyAvailableMarketValues() {
        // D-EQUITY-PARTIAL: si solo 1 de 2 tickers tiene precio, marketValue refleja
        // la suma parcial. P&L también parcial. Cliente coherente con marketDataAvailable=partial.
        overrideBalance(new BigDecimal("5000.00"));
        seedPosition("AAPL", 10, "189.45"); // costBasis 1894.50
        seedPosition("MSFT", 5, "412.00");  // costBasis 2060.00
        Map<String, BigDecimal> prices = new HashMap<>();
        prices.put("AAPL", new BigDecimal("193.20")); // marketValue 1932.00
        prices.put("MSFT", null);                       // sin precio

        AccountEquityDto equity = portfolioService.getAccountEquity(userId, prices);

        // costBasis es la suma completa (no depende de precios)
        assertThat(equity.costBasisTotal()).isEqualTo("3954.50");
        // marketValue solo cuenta AAPL
        assertThat(equity.positionsMarketValue()).isEqualTo("1932.00");
        // equity = 5000 + 1932 = 6932
        assertThat(equity.equity()).isEqualTo("6932.00");
        // pnl = 1932 − 3954.50 = -2022.50
        assertThat(equity.unrealizedPnL()).isEqualTo("-2022.50");
        // pct = -2022.50 / 3954.50 × 100 ≈ -51.14
        assertThat(equity.unrealizedPnLPct()).isEqualTo("-51.14");
    }

    @Test
    void getAccountEquity_nullPricesMap_treatsAsEmpty() {
        // Defensa: si por bug el caller pasa null, no NPE — tratamos como sin precios.
        overrideBalance(new BigDecimal("5000.00"));
        seedPosition("AAPL", 10, "189.45");

        AccountEquityDto equity = portfolioService.getAccountEquity(userId, null);

        assertThat(equity.balance()).isEqualTo("5000.00");
        assertThat(equity.positionsMarketValue()).isNull();
        assertThat(equity.costBasisTotal()).isEqualTo("1894.50");
    }
}
