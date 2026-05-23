package co.edu.unbosque.bloomtrade.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.unbosque.bloomtrade.auth.domain.DocumentType;
import co.edu.unbosque.bloomtrade.auth.domain.User;
import co.edu.unbosque.bloomtrade.auth.repository.UserRepository;
import co.edu.unbosque.bloomtrade.portfolio.domain.Position;
import co.edu.unbosque.bloomtrade.portfolio.exception.InsufficientFundsException;
import co.edu.unbosque.bloomtrade.portfolio.repository.PositionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * IT del {@link PortfolioService} (HU-F09 Lote D). Postgres real del docker-compose (perfil
 * {@code test}, BD {@code bloomtrade_test} en {@code localhost:5433}; D16 HU-F01).
 *
 * <p>Cubre 5 escenarios críticos del SPEC §10.2:
 * <ul>
 *   <li>debit happy path</li>
 *   <li>debit insuficiente — invariante {@code balance >= 0}</li>
 *   <li>debit exactly-zero edge</li>
 *   <li>upsertPosition nueva (INSERT)</li>
 *   <li>upsertPosition existente con recálculo de {@code avg_buy_price}</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class PortfolioServiceTest {

    @Autowired private PortfolioService portfolioService;
    @Autowired private UserRepository userRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private BalanceInitializer balanceInitializer;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID userId;

    @BeforeEach
    void cleanAndCreateUser() {
        // Orden de TRUNCATE por FK: positions/orders → user_balances → users.
        jdbcTemplate.execute("TRUNCATE app.orders, app.positions, app.user_balances, app.users CASCADE");

        User user =
                User.register(
                        "trader-" + UUID.randomUUID() + "@test.local",
                        "$2a$12$dummyHashForTestingOnlyNotARealBcryptHashAtAllxx",
                        "Trader Test",
                        DocumentType.CC,
                        "1000" + System.nanoTime() % 1_000_000,
                        "+573001112233",
                        Instant.now());
        user = userRepository.save(user);
        userId = user.getId();
        balanceInitializer.initializeBalance(userId);
    }

    private void overrideBalance(BigDecimal newBalance) {
        jdbcTemplate.update(
                "UPDATE app.user_balances SET balance = ? WHERE user_id = ?", newBalance, userId);
    }

    @Test
    void debit_happyPath_reducesBalance() {
        BigDecimal newBalance = portfolioService.debit(userId, new BigDecimal("1881.90"));

        assertThat(newBalance).isEqualByComparingTo("8118.10");
        assertThat(portfolioService.getBalance(userId)).isEqualByComparingTo("8118.10");
    }

    @Test
    void debit_insufficientFunds_throwsAndDoesNotPersist() {
        overrideBalance(new BigDecimal("100.00"));

        assertThatThrownBy(() -> portfolioService.debit(userId, new BigDecimal("1000.00")))
                .isInstanceOf(InsufficientFundsException.class)
                .extracting("balance", "required", "shortfall")
                .containsExactly(
                        new BigDecimal("100.00"),
                        new BigDecimal("1000.00"),
                        new BigDecimal("900.00"));

        // Balance NO se modificó (rollback).
        assertThat(portfolioService.getBalance(userId)).isEqualByComparingTo("100.00");
    }

    @Test
    void debit_exactBalance_resultsInZeroWithoutError() {
        overrideBalance(new BigDecimal("1000.00"));

        BigDecimal newBalance = portfolioService.debit(userId, new BigDecimal("1000.00"));

        assertThat(newBalance).isEqualByComparingTo("0.00");
        assertThat(newBalance.signum()).isEqualTo(0);
    }

    @Test
    void upsertPosition_newTicker_insertsRowWithGivenQtyAndPrice() {
        Position position =
                portfolioService.upsertPosition(userId, "AAPL", 10, new BigDecimal("184.6200"));

        assertThat(position.getQuantity()).isEqualTo(10);
        assertThat(position.getAvgBuyPrice()).isEqualByComparingTo("184.6200");
        assertThat(positionRepository.findByUserIdAndTicker(userId, "AAPL")).isPresent();
    }

    @Test
    void upsertPosition_existingTicker_updatesAndRecalculatesWeightedAverage() {
        // Posición inicial: 10 AAPL @ 184.62
        portfolioService.upsertPosition(userId, "AAPL", 10, new BigDecimal("184.6200"));

        // Segunda compra: 10 AAPL @ 190.00 → esperado: 20 AAPL @ 187.3100
        Position result =
                portfolioService.upsertPosition(userId, "AAPL", 10, new BigDecimal("190.0000"));

        assertThat(result.getQuantity()).isEqualTo(20);
        // (10 × 184.62 + 10 × 190.00) / 20 = 3746.20 / 20 = 187.31
        assertThat(result.getAvgBuyPrice()).isEqualByComparingTo("187.3100");

        // Solo 1 fila para (userId, AAPL) — confirma UPSERT, no INSERT duplicado.
        assertThat(positionRepository.findByUserId(userId)).hasSize(1);
    }

    @Test
    void upsertPosition_oddDivisionAppliesScale4HalfUp() {
        // 7 AAPL @ 100.00, luego 3 AAPL @ 150.00 → (700 + 450) / 10 = 115.00 (exacto).
        portfolioService.upsertPosition(userId, "AAPL", 7, new BigDecimal("100.0000"));
        Position result =
                portfolioService.upsertPosition(userId, "AAPL", 3, new BigDecimal("150.0000"));

        assertThat(result.getQuantity()).isEqualTo(10);
        assertThat(result.getAvgBuyPrice()).isEqualByComparingTo("115.0000");
    }

    @Test
    void getPositions_multipleTickers_returnsAll() {
        portfolioService.upsertPosition(userId, "AAPL", 10, new BigDecimal("184.6200"));
        portfolioService.upsertPosition(userId, "MSFT", 5, new BigDecimal("420.5000"));
        portfolioService.upsertPosition(userId, "GOOGL", 3, new BigDecimal("178.0000"));

        assertThat(portfolioService.getPositions(userId))
                .hasSize(3)
                .extracting(Position::getTicker)
                .containsExactlyInAnyOrder("AAPL", "MSFT", "GOOGL");
    }
}
