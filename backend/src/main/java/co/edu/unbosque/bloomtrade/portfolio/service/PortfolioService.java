package co.edu.unbosque.bloomtrade.portfolio.service;

import co.edu.unbosque.bloomtrade.dashboard.dto.AccountEquityDto;
import co.edu.unbosque.bloomtrade.portfolio.domain.Position;
import co.edu.unbosque.bloomtrade.portfolio.domain.UserBalance;
import co.edu.unbosque.bloomtrade.portfolio.exception.InsufficientFundsException;
import co.edu.unbosque.bloomtrade.portfolio.repository.PositionRepository;
import co.edu.unbosque.bloomtrade.portfolio.repository.UserBalanceRepository;
import co.edu.unbosque.bloomtrade.trading.domain.Order;
import co.edu.unbosque.bloomtrade.trading.domain.OrderStatus;
import co.edu.unbosque.bloomtrade.trading.exception.InsufficientSharesException;
import co.edu.unbosque.bloomtrade.trading.exception.ShortSellingNotAllowedException;
import co.edu.unbosque.bloomtrade.trading.repository.OrderRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operaciones de portafolio del inversionista (HU-F09 Lote D + HU-F10 Lote A — ARCH §3
 * PortfolioService).
 *
 * <p>Responsabilidades:
 * <ul>
 *   <li>{@link #debit} — descontar fondos del saldo con lock pessimistic (HU-F09 D13)</li>
 *   <li>{@link #credit} — sumar fondos al saldo tras venta (HU-F10 D14)</li>
 *   <li>{@link #upsertPosition} — incrementar la posición de un ticker (recalcula avg_buy_price
 *       como promedio ponderado vía {@link Position#incrementBy})</li>
 *   <li>{@link #decrementPosition} — restar a la posición tras venta con lock pessimistic;
 *       DELETE de la fila si la cantidad resultante es 0 (HU-F10 D1, D8)</li>
 *   <li>{@link #getBalance} / {@link #getPositions} / {@link #findPosition} — lecturas readOnly</li>
 * </ul>
 *
 * <p>Compañero arquitectónico: {@code BalanceInitializer} (ya existente, HU-F01) crea el
 * balance inicial. Este {@code PortfolioService} lo muta tras la ejecución de cada orden.
 *
 * <p><b>Orden canónico de locks (HU-F10 D12 + D17 emergente Lote D)</b>: balances → positions.
 * Cuando una operación necesita ambos locks en la misma transacción, debe adquirirlos en
 * ese orden para evitar deadlocks con otra operación concurrente.
 * <ul>
 *   <li>BUY: {@link #debit} bloquea balances; el subsiguiente {@link #upsertPosition}
 *       toca positions vía Hibernate dirty checking sin lock pessimistic explícito.</li>
 *   <li>SELL: {@link #validateSellable} bloquea balances PRIMERO (aunque vender no requiera
 *       pre-validar saldo) y después positions. El {@link #credit} posterior reusa el lock
 *       balances ya tomado; {@link #decrementPosition} reusa el lock positions.</li>
 * </ul>
 * Sin el lock-balances-PRIMERO en SELL, dos órdenes BUY y SELL concurrentes del mismo usuario
 * sobre el mismo ticker entran en ciclo de espera y Postgres dispara "deadlock detected".
 * El test {@code TradingServiceSellConcurrencyIT#concurrency_buyAndSellSameTicker_*} verifica.
 */
@Service
public class PortfolioService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioService.class);

    private final UserBalanceRepository userBalanceRepository;
    private final PositionRepository positionRepository;
    private final OrderRepository orderRepository;

    public PortfolioService(
            UserBalanceRepository userBalanceRepository,
            PositionRepository positionRepository,
            OrderRepository orderRepository) {
        this.userBalanceRepository = userBalanceRepository;
        this.positionRepository = positionRepository;
        this.orderRepository = orderRepository;
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
     * Suma {@code amount} al saldo del usuario (operación inversa de {@link #debit}). HU-F10:
     * se invoca tras una venta ejecutada exitosamente para acreditar el producto neto
     * ({@code execution_unit_price × quantity − commission}).
     *
     * <p>Toma lock pessimistic sobre {@code app.user_balances} igual que {@link #debit} —
     * serializa créditos concurrentes (poco frecuentes pero posibles si dos ventas del mismo
     * usuario se ejecutan a la vez).
     *
     * <p>HU-F10 D14: NO usa {@code noRollbackFor}. A diferencia de {@link #debit}, los errores
     * de {@code credit} ({@link IllegalArgumentException}, {@link IllegalStateException}) son
     * defectos de programación o estado corrupto — el rollback completo es la respuesta correcta.
     *
     * @return el balance resultante tras el crédito
     * @throws IllegalArgumentException si {@code amount <= 0}
     * @throws IllegalStateException si el usuario no tiene fila en {@code app.user_balances}
     */
    @Transactional
    public BigDecimal credit(UUID userId, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Credit amount debe ser > 0, recibido: " + amount);
        }
        UserBalance balance =
                userBalanceRepository
                        .findByUserIdForUpdate(userId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Balance no encontrado para userId=" + userId));
        balance.applyCredit(amount);
        log.debug(
                "Crédito aplicado userId={} amount={} newBalance={}",
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

    /**
     * Valida que el usuario tenga posición suficiente para vender SIN mutar nada (HU-F10
     * Lote B — patrón "validate-before-Alpaca / mutate-after-Alpaca"). Adquiere lock pessimistic
     * sobre la fila {@code (userId, ticker)} de {@code app.positions} que se retiene durante la
     * transacción del caller — un decrementPosition subsiguiente en la misma {@code @Transactional}
     * encuentra el lock ya tomado y reutiliza.
     *
     * <p>Justificación del split: si decrementáramos antes de llamar a Alpaca, un Alpaca-rejected
     * requeriría re-incrementar (más código, más bugs potenciales con el avg_buy_price). Mantener
     * el lock entre validate y mutate es seguro porque ambas operaciones viven en la misma
     * {@code @Transactional} del caller ({@code TradingService.handleSellTx}).
     *
     * <p>{@code noRollbackFor} análogo a {@link #debit} (HU-F09 D24, D27): cuando las excepciones
     * de validación se lanzan dentro de un método {@code @Transactional} anidado, Spring marca
     * la TX outer como rollback-only por default — el commit posterior del INSERT de la orden
     * REJECTED fallaría con {@code UnexpectedRollbackException}. Excluir estas excepciones del
     * marcado preserva la fila REJECTED en BD como historial visible para el usuario.
     *
     * @return la {@link Position} con el lock pessimistic retenido para la transacción actual.
     * @throws ShortSellingNotAllowedException si no existe fila o quantity = 0
     * @throws InsufficientSharesException si quantity < sellQty
     */
    @Transactional(
            noRollbackFor = {
                ShortSellingNotAllowedException.class,
                InsufficientSharesException.class
            })
    public Position validateSellable(UUID userId, String ticker, int sellQty) {
        // D17 Lote D — orden canónico de locks balances→positions para evitar deadlock con
        // BUY concurrente del mismo usuario+ticker (BUY adquiere balances en debit, después
        // positions en upsert). Tomamos el lock en balances PRIMERO aunque la venta no toque
        // saldo hasta el credit posterior — la sesión queda dueña de ambos en el orden correcto.
        // Trade-off aceptado: dos SELLs concurrentes del mismo user (incluso de tickers
        // distintos) se serializan en balances. Para MVP single-user es insignificante.
        userBalanceRepository.findByUserIdForUpdate(userId);

        Position position =
                positionRepository
                        .findByUserIdAndTickerForUpdate(userId, ticker)
                        .orElseThrow(() -> new ShortSellingNotAllowedException(ticker, sellQty));
        if (position.getQuantity() == 0) {
            throw new ShortSellingNotAllowedException(ticker, sellQty);
        }
        if (position.getQuantity() < sellQty) {
            throw new InsufficientSharesException(position.getQuantity(), sellQty, ticker);
        }
        return position;
    }

    /**
     * Decrementa la posición del usuario en el ticker tras una venta ejecutada (HU-F10).
     * Adquiere lock pessimistic sobre la fila {@code (userId, ticker)} de {@code app.positions}.
     *
     * <p>Comportamiento (HU-F10 D1, D8):
     * <ul>
     *   <li>Si NO existe fila o {@code quantity = 0} → {@link ShortSellingNotAllowedException}.</li>
     *   <li>Si {@code quantity < sellQuantity} → {@link InsufficientSharesException}.</li>
     *   <li>Si {@code quantity == sellQuantity} → DELETE de la fila y retorna
     *       {@link Optional#empty()}.</li>
     *   <li>Si {@code quantity > sellQuantity} → UPDATE con la cantidad nueva (el
     *       {@code avg_buy_price} no se modifica) y retorna {@code Optional.of(updated)}.</li>
     * </ul>
     *
     * @return {@link Optional#empty()} si la fila fue borrada por liquidación total;
     *     {@code Optional.of(position)} con la cantidad actualizada si quedó tenencia.
     */
    @Transactional(
            noRollbackFor = {
                ShortSellingNotAllowedException.class,
                InsufficientSharesException.class
            })
    public Optional<Position> decrementPosition(UUID userId, String ticker, int sellQuantity) {
        Position position =
                positionRepository
                        .findByUserIdAndTickerForUpdate(userId, ticker)
                        .orElseThrow(
                                () -> new ShortSellingNotAllowedException(ticker, sellQuantity));
        if (position.getQuantity() == 0) {
            // Defensa en profundidad — D1 borra al llegar a 0, pero si por alguna razón
            // sobrevivió una fila con qty=0, tratarla como "sin posición".
            throw new ShortSellingNotAllowedException(ticker, sellQuantity);
        }
        if (position.getQuantity() < sellQuantity) {
            throw new InsufficientSharesException(position.getQuantity(), sellQuantity, ticker);
        }
        position.decrementBy(sellQuantity);
        if (position.isDepleted()) {
            // Hibernate auto-flushea el UPDATE antes del DELETE (@Modifying). El extra
            // UPDATE → DELETE es I/O wasted pero correcto. En Lote F evaluar si vale
            // optimizar (delete directo sin tocar el entity).
            positionRepository.deleteByUserIdAndTicker(userId, ticker);
            log.debug(
                    "Position liquidada y borrada userId={} ticker={} (sellQty={})",
                    userId,
                    ticker,
                    sellQuantity);
            return Optional.empty();
        }
        log.debug(
                "Position decrementada userId={} ticker={} sellQty={} nuevaQty={}",
                userId,
                ticker,
                sellQuantity,
                position.getQuantity());
        return Optional.of(position);
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

    /**
     * Devuelve la entidad {@link UserBalance} completa para acceder a {@code updatedAt} y
     * {@code currency}. Usar {@link #getBalance(UUID)} si solo se necesita el monto.
     *
     * <p>HU-F21 — alimenta el {@code BalanceResponse} de
     * {@code GET /api/v1/portfolio/balance} con su {@code lastUpdatedAt}.
     *
     * @throws IllegalStateException si no existe fila para el {@code userId} (no debería
     *     ocurrir — {@code BalanceInitializer} la crea en HU-F01).
     */
    @Transactional(readOnly = true)
    public UserBalance getBalanceEntity(UUID userId) {
        return userBalanceRepository
                .findById(userId)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Balance no encontrado para userId=" + userId));
    }

    /**
     * HU-F16 D12 — filtro defensivo {@code quantity > 0}. Si por un bug futuro sobreviviera una
     * fila con {@code quantity = 0} (HU-F10 D1 borra la fila al llegar a 0), el listado del
     * portafolio NO la incluye.
     */
    @Transactional(readOnly = true)
    public List<Position> getPositions(UUID userId) {
        return positionRepository.findByUserIdAndQuantityGreaterThanOrderByTicker(userId, 0);
    }

    /**
     * HU-F16 D4 — devuelve órdenes encoladas en Alpaca para la sección {@code pendingOrders[]}
     * de {@code GET /api/v1/portfolio/positions}. Solo {@code PENDING + alpaca_order_id NOT NULL}
     * (órdenes en estado intermedio antes del submit a Alpaca NO se exponen).
     *
     * <p>Mitigación de la deuda viva #8/#12 del AGENTS.md handoff: visibilidad UX de órdenes
     * cuyo efecto en BD (saldo debitado en BUY queued, posición decrementada en SELL queued)
     * aún no se reconcilió con un fill real de Alpaca.
     */
    @Transactional(readOnly = true)
    public List<Order> getPendingOrders(UUID userId) {
        return orderRepository.findByUserIdAndStatusAndAlpacaOrderIdIsNotNullOrderBySubmittedAtDesc(
                userId, OrderStatus.PENDING);
    }

    /** Lookup readOnly de la posición para el quote SELL (HU-F10 D15: sin lock pessimistic). */
    @Transactional(readOnly = true)
    public Optional<Position> findPosition(UUID userId, String ticker) {
        return positionRepository.findByUserIdAndTicker(userId, ticker);
    }

    /**
     * HU-F18 plan D9 — calcula el equity total del usuario y P&L no realizado vs cost basis.
     *
     * <p>Equity = {@code balance + Σ(qty × currentPrice)} sobre todas las posiciones con qty>0.
     * Cost basis total = {@code Σ(qty × avgBuyPrice)} (NO se descuentan comisiones; consistente
     * con HU-F16 D11). P&L no realizado = {@code marketValue − costBasis} (signed). Porcentaje
     * = {@code (pnl / costBasis) × 100}, scale=2 HALF_UP.
     *
     * <p>Semántica null (plan D-EQUITY-PARTIAL):
     * <ul>
     *   <li>Sin posiciones (qty&gt;0=∅): {@code marketValue="0.00"}, {@code equity=balance},
     *       {@code costBasis="0.00"}, {@code pnl="0.00"}, {@code pct=null} (no se puede ratio).</li>
     *   <li>Posiciones con precios completos: todos los campos calculados.</li>
     *   <li>Posiciones con todos los prices null: {@code marketValue/equity/pnl/pct=null}.</li>
     *   <li>Posiciones con prices parciales: {@code marketValue/pnl} reflejan solo las que tienen
     *       precio (sum parcial); el cliente ve degradación coherente con
     *       {@code marketDataAvailable="partial"} top-level.</li>
     * </ul>
     *
     * @param userId usuario propietario.
     * @param prices map ticker→precio (puede contener nulls para tickers cuyo fetch falló).
     */
    @Transactional(readOnly = true)
    public AccountEquityDto getAccountEquity(UUID userId, java.util.Map<String, BigDecimal> prices) {
        UserBalance balance = getBalanceEntity(userId);
        String balanceStr = balance.getBalance().setScale(2, RoundingMode.HALF_UP).toPlainString();
        String currency = balance.getCurrency();

        List<Position> positions = getPositions(userId);
        if (positions.isEmpty()) {
            return new AccountEquityDto(
                    balanceStr,
                    "0.00",
                    balanceStr,
                    "0.00",
                    "0.00",
                    null,
                    currency);
        }

        BigDecimal costBasisTotal = BigDecimal.ZERO;
        BigDecimal marketValueTotal = BigDecimal.ZERO;
        int pricedCount = 0;
        for (Position p : positions) {
            BigDecimal qty = BigDecimal.valueOf(p.getQuantity());
            costBasisTotal = costBasisTotal.add(qty.multiply(p.getAvgBuyPrice()));
            BigDecimal price = prices == null ? null : prices.get(p.getTicker());
            if (price != null) {
                marketValueTotal = marketValueTotal.add(qty.multiply(price));
                pricedCount++;
            }
        }
        costBasisTotal = costBasisTotal.setScale(2, RoundingMode.HALF_UP);

        if (pricedCount == 0) {
            // Sin ningún precio: marketValue/equity/pnl/pct todos null. CostBasis sí presente.
            return new AccountEquityDto(
                    balanceStr,
                    null,
                    null,
                    costBasisTotal.toPlainString(),
                    null,
                    null,
                    currency);
        }
        marketValueTotal = marketValueTotal.setScale(2, RoundingMode.HALF_UP);
        BigDecimal equity = balance.getBalance().add(marketValueTotal).setScale(2, RoundingMode.HALF_UP);
        BigDecimal pnl = marketValueTotal.subtract(costBasisTotal).setScale(2, RoundingMode.HALF_UP);
        String pctStr;
        if (costBasisTotal.signum() == 0) {
            pctStr = null;
        } else {
            BigDecimal pct =
                    pnl.multiply(BigDecimal.valueOf(100))
                            .divide(costBasisTotal, 2, RoundingMode.HALF_UP);
            pctStr = pct.toPlainString();
        }
        return new AccountEquityDto(
                balanceStr,
                marketValueTotal.toPlainString(),
                equity.toPlainString(),
                costBasisTotal.toPlainString(),
                pnl.toPlainString(),
                pctStr,
                currency);
    }
}
