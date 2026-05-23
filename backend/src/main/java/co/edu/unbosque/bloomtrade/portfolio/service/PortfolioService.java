package co.edu.unbosque.bloomtrade.portfolio.service;

import co.edu.unbosque.bloomtrade.portfolio.domain.Position;
import co.edu.unbosque.bloomtrade.portfolio.domain.UserBalance;
import co.edu.unbosque.bloomtrade.portfolio.exception.InsufficientFundsException;
import co.edu.unbosque.bloomtrade.portfolio.repository.PositionRepository;
import co.edu.unbosque.bloomtrade.portfolio.repository.UserBalanceRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operaciones de portafolio del inversionista (HU-F09 Lote D — ARCH §3 PortfolioService).
 *
 * <p>Responsabilidades:
 * <ul>
 *   <li>{@link #debit} — descontar fondos del saldo con lock pessimistic (D13)</li>
 *   <li>{@link #upsertPosition} — incrementar la posición de un ticker (recalcula avg_buy_price
 *       como promedio ponderado vía {@link Position#incrementBy})</li>
 *   <li>{@link #getBalance} / {@link #getPositions} — lecturas readOnly</li>
 * </ul>
 *
 * <p>Compañero arquitectónico: {@code BalanceInitializer} (ya existente, HU-F01) crea el
 * balance inicial. Este {@code PortfolioService} lo muta tras la ejecución de cada orden.
 */
@Service
public class PortfolioService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioService.class);

    private final UserBalanceRepository userBalanceRepository;
    private final PositionRepository positionRepository;

    public PortfolioService(
            UserBalanceRepository userBalanceRepository, PositionRepository positionRepository) {
        this.userBalanceRepository = userBalanceRepository;
        this.positionRepository = positionRepository;
    }

    /**
     * Descuenta {@code amount} del saldo del usuario. Toma lock pessimistic
     * ({@code SELECT ... FOR UPDATE}) para serializar débitos concurrentes (D13).
     *
     * <p>{@code noRollbackFor=InsufficientFundsException}: cuando los fondos son insuficientes
     * propagamos la excepción sin marcar la tx outer como rollback-only. El caller
     * ({@code TradingService.placeOrderTx}) la captura, marca la orden como REJECTED, y
     * commitea la tx — la fila REJECTED se preserva.
     *
     * @return el balance resultante tras el débito (sin {@code .setScale} adicional — preserva
     *     {@code NUMERIC(19,2)} del agregado).
     * @throws InsufficientFundsException si el balance actual es menor que {@code amount}
     * @throws IllegalStateException si el usuario no tiene fila en {@code app.user_balances}
     *     (no debería ocurrir — {@code BalanceInitializer} la crea al registrarse)
     */
    @Transactional(noRollbackFor = InsufficientFundsException.class)
    public BigDecimal debit(UUID userId, BigDecimal amount) {
        UserBalance balance =
                userBalanceRepository
                        .findByUserIdForUpdate(userId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Balance no encontrado para userId=" + userId));
        if (balance.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException(balance.getBalance(), amount);
        }
        balance.applyDebit(amount);
        log.debug(
                "Débito aplicado userId={} amount={} newBalance={}",
                userId,
                amount,
                balance.getBalance());
        return balance.getBalance();
    }

    /**
     * Incrementa la posición del usuario en el ticker. Si no existe fila para
     * {@code (userId, ticker)}, INSERT con la cantidad y precio iniciales. Si existe, UPDATE
     * recalculando {@code avg_buy_price} como promedio ponderado (ver
     * {@link Position#incrementBy}).
     *
     * @return la {@link Position} resultante (nueva o actualizada).
     */
    @Transactional
    public Position upsertPosition(
            UUID userId, String ticker, int additionalQty, BigDecimal unitPrice) {
        return positionRepository
                .findByUserIdAndTicker(userId, ticker)
                .map(
                        existing -> {
                            existing.incrementBy(additionalQty, unitPrice);
                            log.debug(
                                    "Position incrementada userId={} ticker={} qty={} avgPrice={}",
                                    userId,
                                    ticker,
                                    existing.getQuantity(),
                                    existing.getAvgBuyPrice());
                            return existing;
                        })
                .orElseGet(
                        () -> {
                            Position created =
                                    positionRepository.save(
                                            Position.newPosition(userId, ticker, additionalQty, unitPrice));
                            log.debug(
                                    "Position creada userId={} ticker={} qty={} avgPrice={}",
                                    userId,
                                    ticker,
                                    additionalQty,
                                    unitPrice);
                            return created;
                        });
    }

    @Transactional(readOnly = true)
    public BigDecimal getBalance(UUID userId) {
        return userBalanceRepository
                .findById(userId)
                .map(UserBalance::getBalance)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Balance no encontrado para userId=" + userId));
    }

    @Transactional(readOnly = true)
    public List<Position> getPositions(UUID userId) {
        return positionRepository.findByUserId(userId);
    }
}
