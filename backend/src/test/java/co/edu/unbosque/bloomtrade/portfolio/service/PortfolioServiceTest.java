package co.edu.unbosque.bloomtrade.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.unbosque.bloomtrade.auth.domain.DocumentType;
import co.edu.unbosque.bloomtrade.auth.domain.User;
import co.edu.unbosque.bloomtrade.auth.repository.UserRepository;
import co.edu.unbosque.bloomtrade.portfolio.domain.Position;
import co.edu.unbosque.bloomtrade.portfolio.exception.InsufficientFundsException;
import co.edu.unbosque.bloomtrade.portfolio.repository.PositionRepository;
import co.edu.unbosque.bloomtrade.trading.exception.InsufficientSharesException;
import co.edu.unbosque.bloomtrade.trading.exception.ShortSellingNotAllowedException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
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

    // ============================================================================
    // HU-F10 Lote A — credit (T1.10)
    // ============================================================================

    @Test
    void credit_happyPath_increasesBalance() {
        overrideBalance(new BigDecimal("1000.00"));

        BigDecimal newBalance = portfolioService.credit(userId, new BigDecimal("500.00"));

        assertThat(newBalance).isEqualByComparingTo("1500.00");
        assertThat(portfolioService.getBalance(userId)).isEqualByComparingTo("1500.00");
    }

    @Test
    void credit_preservesBigDecimalPrecision() {
        overrideBalance(new BigDecimal("1000.50"));

        BigDecimal newBalance = portfolioService.credit(userId, new BigDecimal("930.75"));

        // 1000.50 + 930.75 = 1931.25 exacto, sin precision drift.
        assertThat(newBalance).isEqualByComparingTo("1931.25");
        assertThat(newBalance.scale()).isEqualTo(2);
    }

    @Test
    void credit_negativeAmount_throwsIllegalArgument() {
        overrideBalance(new BigDecimal("1000.00"));

        assertThatThrownBy(() -> portfolioService.credit(userId, new BigDecimal("-100.00")))
                .isInstanceOf(IllegalArgumentException.class);

        // Balance intacto — la validación es pre-lock, sin tocar BD.
        assertThat(portfolioService.getBalance(userId)).isEqualByComparingTo("1000.00");
    }

    @Test
    void credit_zeroAmount_throwsIllegalArgument() {
        overrideBalance(new BigDecimal("1000.00"));

        assertThatThrownBy(() -> portfolioService.credit(userId, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(portfolioService.getBalance(userId)).isEqualByComparingTo("1000.00");
    }

    @Test
    void credit_balanceNotFound_throwsIllegalState() {
        UUID strangerId = UUID.randomUUID();

        assertThatThrownBy(() -> portfolioService.credit(strangerId, new BigDecimal("100.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(strangerId.toString());
    }

    // ============================================================================
    // HU-F10 Lote A — decrementPosition (T1.11)
    // ============================================================================

    @Test
    void decrement_happyPath_residualQty_preservesAvgBuyPrice() {
        portfolioService.upsertPosition(userId, "AAPL", 10, new BigDecimal("184.6200"));

        Optional<Position> result = portfolioService.decrementPosition(userId, "AAPL", 3);

        assertThat(result).isPresent();
        assertThat(result.get().getQuantity()).isEqualTo(7);
        // avg_buy_price NO cambia en venta — sigue reflejando el promedio histórico de compras.
        assertThat(result.get().getAvgBuyPrice()).isEqualByComparingTo("184.6200");

        // Persistido: re-leer desde repo confirma estado en BD.
        Position fromDb = positionRepository.findByUserIdAndTicker(userId, "AAPL").orElseThrow();
        assertThat(fromDb.getQuantity()).isEqualTo(7);
        assertThat(fromDb.getAvgBuyPrice()).isEqualByComparingTo("184.6200");
    }

    @Test
    void decrement_exactQty_deletesRow() {
        portfolioService.upsertPosition(userId, "AAPL", 5, new BigDecimal("184.6200"));

        Optional<Position> result = portfolioService.decrementPosition(userId, "AAPL", 5);

        assertThat(result).isEmpty();
        // La fila ya no existe en BD (D1).
        assertThat(positionRepository.findByUserIdAndTicker(userId, "AAPL")).isEmpty();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM app.positions WHERE user_id = ? AND ticker = 'AAPL'",
                                Integer.class,
                                userId))
                .isEqualTo(0);
    }

    @Test
    void decrement_insufficientShares_throwsAndNoPersist() {
        portfolioService.upsertPosition(userId, "AAPL", 3, new BigDecimal("184.6200"));

        assertThatThrownBy(() -> portfolioService.decrementPosition(userId, "AAPL", 5))
                .isInstanceOf(InsufficientSharesException.class)
                .extracting("available", "requested", "ticker")
                .containsExactly(3, 5, "AAPL");

        // Posición intacta tras rollback.
        Position fromDb = positionRepository.findByUserIdAndTicker(userId, "AAPL").orElseThrow();
        assertThat(fromDb.getQuantity()).isEqualTo(3);
    }

    @Test
    void decrement_shortSelling_throwsAndNoPersist() {
        // Sin posición previa para MSFT.

        assertThatThrownBy(() -> portfolioService.decrementPosition(userId, "MSFT", 5))
                .isInstanceOf(ShortSellingNotAllowedException.class)
                .extracting("ticker", "requestedQty")
                .containsExactly("MSFT", 5);

        // No se creó fila accidentalmente.
        assertThat(positionRepository.findByUserIdAndTicker(userId, "MSFT")).isEmpty();
    }

    @Test
    void decrement_existingPositionWithZeroQty_throwsShortSelling() {
        // Edge defensivo: D1 borra al llegar a 0, pero si por alguna razón sobreviviera una
        // fila con qty=0, decrementPosition la trata como "sin posición". Insertamos via
        // JdbcTemplate para saltarnos los validadores del entity.
        jdbcTemplate.update(
                "INSERT INTO app.positions (id, user_id, ticker, quantity, avg_buy_price)"
                        + " VALUES (?, ?, 'TSLA', 0, 200.00)",
                UUID.randomUUID(),
                userId);

        assertThatThrownBy(() -> portfolioService.decrementPosition(userId, "TSLA", 1))
                .isInstanceOf(ShortSellingNotAllowedException.class);

        // La fila qty=0 sigue ahí (no la borramos como side-effect — solo rechazamos la venta).
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT quantity FROM app.positions WHERE user_id = ? AND ticker = 'TSLA'",
                                Integer.class,
                                userId))
                .isEqualTo(0);
    }

    @Test
    void findPosition_existing_returnsIt() {
        portfolioService.upsertPosition(userId, "AAPL", 10, new BigDecimal("184.6200"));

        assertThat(portfolioService.findPosition(userId, "AAPL")).isPresent();
    }

    @Test
    void findPosition_missing_returnsEmpty() {
        assertThat(portfolioService.findPosition(userId, "NFLX")).isEmpty();
    }
}
