package co.edu.unbosque.bloomtrade.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.unbosque.bloomtrade.auth.domain.DocumentType;
import co.edu.unbosque.bloomtrade.auth.domain.User;
import co.edu.unbosque.bloomtrade.auth.repository.UserRepository;
import co.edu.unbosque.bloomtrade.portfolio.exception.InsufficientFundsException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Test de concurrencia del lock pessimistic en {@code UserBalanceRepository#findByUserIdForUpdate}
 * (HU-F09 D13). Invoca {@link PortfolioService#debit} desde 2 threads simultáneos sobre el mismo
 * userId con débitos que juntos exceden el saldo. Verifica:
 * <ul>
 *   <li>Exactamente UNA transacción commitea exitosamente</li>
 *   <li>La otra falla con {@link InsufficientFundsException} (re-evaluando saldo tras el commit del primero)</li>
 *   <li>El saldo final refleja UNA sola operación</li>
 * </ul>
 *
 * <p>Cubre el escenario Gherkin SPEC §11.1 "Concurrencia — dos órdenes simultáneas que juntas
 * exceden saldo".
 */
@SpringBootTest
@ActiveProfiles("test")
class PortfolioServiceConcurrencyTest {

    @Autowired private PortfolioService portfolioService;
    @Autowired private UserRepository userRepository;
    @Autowired private BalanceInitializer balanceInitializer;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID userId;

    @BeforeEach
    void cleanAndCreateUserWith2000Balance() {
        jdbcTemplate.execute("TRUNCATE app.orders, app.positions, app.user_balances, app.users CASCADE");
        User user =
                User.register(
                        "concurrent-" + UUID.randomUUID() + "@test.local",
                        "$2a$12$dummyHashForTestingOnlyNotARealBcryptHashAtAllxx",
                        "Concurrent Test",
                        DocumentType.CC,
                        "2000" + System.nanoTime() % 1_000_000,
                        "+573004445566",
                        Instant.now());
        user = userRepository.save(user);
        userId = user.getId();
        balanceInitializer.initializeBalance(userId);
        // 2000.00 alcanza para UNA debit de 1500.00 pero no para dos.
        jdbcTemplate.update(
                "UPDATE app.user_balances SET balance = ? WHERE user_id = ?",
                new BigDecimal("2000.00"),
                userId);
    }

    @Test
    void concurrentDebits_exactlyOneSucceedsTheOtherFailsInsufficientFunds() throws Exception {
        BigDecimal amountEach = new BigDecimal("1500.00");
        // Dos debits de 1500 simultáneos sobre balance 2000 → suma 3000 > 2000.
        // El lock pessimistic serializa: uno commitea (balance pasa de 2000 a 500), el otro
        // re-evalúa y falla con InsufficientFundsException.

        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger insufficientCount = new AtomicInteger();
        AtomicReference<Throwable> unexpectedError = new AtomicReference<>();

        try {
            CompletableFuture<Void> f1 = launchDebit(executor, amountEach, successCount, insufficientCount, unexpectedError);
            CompletableFuture<Void> f2 = launchDebit(executor, amountEach, successCount, insufficientCount, unexpectedError);

            CompletableFuture.allOf(f1, f2).get();
        } finally {
            executor.shutdown();
        }

        assertThat(unexpectedError.get()).as("Sin excepciones inesperadas").isNull();
        assertThat(successCount.get()).as("Exactamente UN débito commiteado").isEqualTo(1);
        assertThat(insufficientCount.get()).as("Exactamente UNA InsufficientFundsException").isEqualTo(1);

        // Balance final = 2000 - 1500 = 500.00 (solo el commiteado se aplicó).
        assertThat(portfolioService.getBalance(userId)).isEqualByComparingTo("500.00");
    }

    private CompletableFuture<Void> launchDebit(
            ExecutorService executor,
            BigDecimal amount,
            AtomicInteger successCount,
            AtomicInteger insufficientCount,
            AtomicReference<Throwable> unexpectedError) {
        return CompletableFuture.runAsync(
                () -> {
                    try {
                        portfolioService.debit(userId, amount);
                        successCount.incrementAndGet();
                    } catch (InsufficientFundsException e) {
                        insufficientCount.incrementAndGet();
                    } catch (Throwable t) {
                        unexpectedError.compareAndSet(null, t);
                    }
                },
                executor);
    }
}
