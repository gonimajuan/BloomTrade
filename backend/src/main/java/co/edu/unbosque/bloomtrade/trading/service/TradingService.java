package co.edu.unbosque.bloomtrade.trading.service;

import co.edu.unbosque.bloomtrade.admin.service.CommissionManager;
import co.edu.unbosque.bloomtrade.admin.service.MarketScheduleManager;
import co.edu.unbosque.bloomtrade.auth.domain.User;
import co.edu.unbosque.bloomtrade.auth.domain.UserStatus;
import co.edu.unbosque.bloomtrade.auth.exception.AccountNotActiveException;
import co.edu.unbosque.bloomtrade.auth.profile.catalog.AllowedTickers;
import co.edu.unbosque.bloomtrade.auth.profile.exception.InvalidTickerException;
import co.edu.unbosque.bloomtrade.auth.repository.UserRepository;
import co.edu.unbosque.bloomtrade.integration.alpaca.AlpacaApiException;
import co.edu.unbosque.bloomtrade.integration.alpaca.AlpacaOrderRejectedException;
import co.edu.unbosque.bloomtrade.integration.alpaca.AlpacaTradingAdapter;
import co.edu.unbosque.bloomtrade.integration.alpaca.MarketDataAdapter;
import co.edu.unbosque.bloomtrade.integration.alpaca.dto.AlpacaOrderResponse;
import co.edu.unbosque.bloomtrade.portfolio.exception.InsufficientFundsException;
import co.edu.unbosque.bloomtrade.portfolio.repository.UserBalanceRepository;
import co.edu.unbosque.bloomtrade.portfolio.service.PortfolioService;
import co.edu.unbosque.bloomtrade.trading.domain.Order;
import co.edu.unbosque.bloomtrade.trading.domain.OrderSide;
import co.edu.unbosque.bloomtrade.trading.domain.OrderType;
import co.edu.unbosque.bloomtrade.trading.dto.OrderResponse;
import co.edu.unbosque.bloomtrade.trading.dto.PlaceOrderRequest;
import co.edu.unbosque.bloomtrade.trading.dto.QuoteRequest;
import co.edu.unbosque.bloomtrade.trading.dto.QuoteResponse;
import co.edu.unbosque.bloomtrade.trading.event.OrderExecutedEvent;
import co.edu.unbosque.bloomtrade.trading.event.OrderFailedEvent;
import co.edu.unbosque.bloomtrade.trading.event.OrderQueuedEvent;
import co.edu.unbosque.bloomtrade.trading.event.OrderRejectedEvent;
import co.edu.unbosque.bloomtrade.trading.exception.InvalidQuantityException;
import co.edu.unbosque.bloomtrade.trading.exception.InvalidSideException;
import co.edu.unbosque.bloomtrade.trading.mapper.OrderMapper;
import co.edu.unbosque.bloomtrade.trading.repository.OrderRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lógica core de HU-F09 — quote y placeOrder (ARCH §3 TradingService).
 *
 * <p>Responsabilidades:
 * <ul>
 *   <li>{@link #quote} — informativo, sin persistencia ni llamadas mutables.</li>
 *   <li>{@link #placeOrder} — transaccional con lock pessimistic sobre balance, llamada
 *       síncrona a Alpaca, polling corto si {@code accepted} no-terminal, persistencia atómica
 *       de la orden + débito + posición, publicación de evento post-commit (Lote F listener).</li>
 * </ul>
 *
 * <p>Decisiones aplicadas: D9 Alpaca-only (data via {@link MarketDataAdapter}), D12 BigDecimal
 * HALF_UP, D13 lock pessimistic, D14 idempotencia por {@code clientOrderId}, D15 events
 * post-commit, D16 estados estrechados.
 *
 * <p>HU-F09 D2: NO se materializan {@code PriorityQueue}+{@code ThreadPool} de ARCH §4 en MVP
 * — request-response síncrono normal. ESC-R1 (1500 órdenes simultáneas) queda como deuda.
 */
@Service
public class TradingService {

    private static final Logger log = LoggerFactory.getLogger(TradingService.class);
    private static final String INVESTOR_ROLE = "INVESTOR";
    private static final String CURRENCY_USD = "USD";

    /** Polling tras {@code accepted}: 3 intentos × 200ms = 600ms max. */
    private static final int MAX_POLL_ATTEMPTS = 3;
    private static final long POLL_INTERVAL_MS = 200L;

    private final OrderRepository orderRepository;
    private final PortfolioService portfolioService;
    private final UserBalanceRepository userBalanceRepository;
    private final MarketDataAdapter marketDataAdapter;
    private final AlpacaTradingAdapter alpacaTradingAdapter;
    private final CommissionManager commissionManager;
    private final MarketScheduleManager marketScheduleManager;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final OrderMapper orderMapper;
    private final int maxQuantityPerOrder;

    /**
     * Serializa procesamiento por {@code clientOrderId} ante requests concurrentes con la misma
     * key (doble-click, browser glitch). Sin esto, el {@code findByClientOrderId} + {@code INSERT}
     * tiene un race: N threads ven empty, N intentan INSERT, el {@code uq_orders_client_order_id}
     * captura N-1 con DIV. El lock garantiza que solo UNO ejecute el flujo completo, los demás
     * vean la fila ya creada y retornen idempotentes.
     *
     * <p>Deuda MVP: el map crece monotónico (entries no se limpian post-commit). Para single-user
     * demo es insignificante. Post-MVP: weak references o cleanup periódico.
     */
    private final ConcurrentHashMap<UUID, Object> clientOrderLocks = new ConcurrentHashMap<>();

    /**
     * Self-injection lazy: permite que {@link #placeOrder} invoque {@link #placeOrderTx} VÍA
     * proxy Spring (no via {@code this}), de modo que el {@code @Transactional} dispare y el
     * commit ocurra DENTRO del {@code synchronized} block. Sin esto el commit ocurriría tras
     * liberar el lock — race con otro thread idéntico-clientOrderId.
     */
    private final TradingService self;

    public TradingService(
            OrderRepository orderRepository,
            PortfolioService portfolioService,
            UserBalanceRepository userBalanceRepository,
            MarketDataAdapter marketDataAdapter,
            AlpacaTradingAdapter alpacaTradingAdapter,
            CommissionManager commissionManager,
            MarketScheduleManager marketScheduleManager,
            UserRepository userRepository,
            ApplicationEventPublisher eventPublisher,
            OrderMapper orderMapper,
            @Value("${trading.max-quantity-per-order:10000}") int maxQuantityPerOrder,
            @Lazy TradingService self) {
        this.orderRepository = orderRepository;
        this.portfolioService = portfolioService;
        this.userBalanceRepository = userBalanceRepository;
        this.marketDataAdapter = marketDataAdapter;
        this.alpacaTradingAdapter = alpacaTradingAdapter;
        this.commissionManager = commissionManager;
        this.marketScheduleManager = marketScheduleManager;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
        this.orderMapper = orderMapper;
        this.maxQuantityPerOrder = maxQuantityPerOrder;
        this.self = self;
    }

    // ─── quote ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public QuoteResponse quote(UUID userId, QuoteRequest request) {
        validateTicker(request.ticker());
        validateQuantity(request.quantity());
        validateSideForBuy(request.side());
        requireActiveAccount(userId);

        BigDecimal unitPrice = marketDataAdapter.getLatestPrice(request.ticker());
        BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(request.quantity()));
        BigDecimal commission = commissionManager.calculate(INVESTOR_ROLE, subtotal);
        BigDecimal total = subtotal.add(commission);
        BigDecimal balance = portfolioService.getBalance(userId);
        boolean sufficientFunds = balance.compareTo(total) >= 0;
        boolean marketOpen = marketScheduleManager.isOpenNow(request.ticker());

        return new QuoteResponse(
                request.ticker(),
                request.side(),
                request.quantity(),
                unitPrice.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                subtotal.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                commission.toPlainString(),
                total.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                CURRENCY_USD,
                balance.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                sufficientFunds,
                marketOpen,
                Instant.now());
    }

    // ─── placeOrder ─────────────────────────────────────────────────────────────

    /**
     * Crear y ejecutar una orden Market. Idempotente vía {@code clientOrderId}: si ya existe
     * fila con esa key, devuelve la orden existente sin re-procesar.
     *
     * <p>Flujo: pre-validar fondos (sin lock) → INSERT order PENDING → POST Alpaca → poll si
     * no-terminal → mark final state + debit + upsertPosition (si EXECUTED) → COMMIT.
     *
     * <p>{@code noRollbackFor} preserva las filas de orden marcadas REJECTED/FAILED a pesar de
     * propagar la excepción al controller (que mapea a 422/502). InsufficientFundsException
     * también: si se dispara post-Alpaca (race), la orden queda REJECTED commiteada; si se
     * dispara en el pre-check (antes del INSERT), no hay nada que commitear.
     */
    /**
     * Entry point — orquesta el lock + delega la lógica transaccional a {@link #placeOrderTx}
     * vía self-proxy. NO @Transactional acá: la tx debe arrancar/commitear DENTRO del
     * synchronized para evitar race find→INSERT.
     */
    public PlaceOrderResult placeOrder(UUID userId, PlaceOrderRequest request) {
        Object lock = clientOrderLocks.computeIfAbsent(request.clientOrderId(), k -> new Object());
        synchronized (lock) {
            return self.placeOrderTx(userId, request);
        }
    }

    @Transactional(
            noRollbackFor = {
                AlpacaApiException.class,
                AlpacaOrderRejectedException.class,
                InsufficientFundsException.class
            })
    public PlaceOrderResult placeOrderTx(UUID userId, PlaceOrderRequest request) {
        // 1. Idempotency check — dentro del lock garantiza visibilidad del INSERT previo
        // si otro thread con misma key ya pasó por acá.
        Optional<Order> existing = orderRepository.findByClientOrderId(request.clientOrderId());
        if (existing.isPresent()) {
            log.info(
                    "Idempotent request: clientOrderId={} ya existe con id={} status={}",
                    request.clientOrderId(),
                    existing.get().getId(),
                    existing.get().getStatus());
            return new PlaceOrderResult(false, orderMapper.toResponse(existing.get()));
        }

        // 2. Validaciones (lanzan excepciones mapeadas en GlobalExceptionHandler).
        validateTicker(request.ticker());
        validateQuantity(request.quantity());
        validateSideForBuy(request.side());
        validateType(request.type());
        requireActiveAccount(userId);

        // 3. Get fresh price + calcular montos.
        BigDecimal unitPrice = marketDataAdapter.getLatestPrice(request.ticker());
        BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(request.quantity()));
        BigDecimal commission = commissionManager.calculate(INVESTOR_ROLE, subtotal);
        BigDecimal quotedTotal = subtotal.add(commission);

        // 4. Validar mercado abierto. D11 stub MVP siempre devuelve true → este check no
        // dispara excepción en MVP. HU-F14 introducirá la lógica real + un MarketClosedException
        // dedicado (con encolamiento, no rechazo). Llamamos isOpenNow para que esté en el call
        // graph y trazas reflejen la intención arquitectónica.
        marketScheduleManager.isOpenNow(request.ticker());

        // 4b. Pre-validación optimista de fondos (SPEC §5.1 paso 13). Evita el INSERT + llamada
        // Alpaca para el caso común "saldo claramente insuficiente". Usamos la projection-only
        // findBalanceProjectionByUserId para NO cargar el entity al L1 cache: si lo cargáramos,
        // el subsiguiente findByUserIdForUpdate de portfolioService.debit podría no emitir
        // SELECT FOR UPDATE real (Hibernate quirk) — rompería la serialización entre threads.
        BigDecimal balance =
                userBalanceRepository
                        .findBalanceProjectionByUserId(userId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Balance no encontrado para userId=" + userId));
        if (balance.compareTo(quotedTotal) < 0) {
            throw new InsufficientFundsException(balance, quotedTotal);
        }

        // 5. INSERT order PENDING.
        Order order =
                Order.newPending(
                        userId,
                        request.clientOrderId(),
                        request.ticker(),
                        request.side(),
                        request.type(),
                        request.quantity(),
                        unitPrice,
                        commission,
                        quotedTotal);
        order = orderRepository.saveAndFlush(order);

        // 6. Submit a Alpaca. Excepciones tipadas se manejan abajo.
        AlpacaOrderResponse alpacaResp;
        try {
            alpacaResp =
                    alpacaTradingAdapter.submitMarketOrder(
                            SubmitMarketOrderCommand.from(order));
        } catch (AlpacaOrderRejectedException rejected) {
            order.markAsRejected("ALPACA_ORDER_REJECTED", rejected.getAlpacaReason());
            order = orderRepository.save(order);
            eventPublisher.publishEvent(
                    new OrderRejectedEvent(
                            order.getId(),
                            userId,
                            order.getTicker(),
                            order.getQuantity(),
                            "ALPACA_ORDER_REJECTED",
                            rejected.getAlpacaReason()));
            throw rejected;
        } catch (AlpacaApiException apiEx) {
            order.markAsFailed("ALPACA_API_ERROR", apiEx.getMessage());
            order = orderRepository.save(order);
            eventPublisher.publishEvent(
                    new OrderFailedEvent(
                            order.getId(),
                            userId,
                            order.getTicker(),
                            order.getQuantity(),
                            "ALPACA_API_ERROR",
                            apiEx.getMessage()));
            throw apiEx;
        }

        // 7. Si no llegó terminal, polear (paper trading suele fill en <500ms; max 600ms total).
        if (!alpacaResp.isTerminal()) {
            alpacaResp = pollUntilTerminal(alpacaResp);
        }

        // 8. Procesar resultado final.
        if (alpacaResp.isFilled()) {
            BigDecimal execPrice = alpacaResp.filledAvgPrice();
            // Computar executionTotal preview SIN mutar el estado de la orden todavía.
            // Patrón: débito primero (puede fallar con race condition); si OK, marcar EXECUTED;
            // si InsufficientFunds, marcar REJECTED desde PENDING — transición válida.
            BigDecimal executionTotal =
                    execPrice
                            .multiply(BigDecimal.valueOf(order.getQuantity()))
                            .add(order.getQuotedCommission());

            BigDecimal newBalance;
            try {
                newBalance = portfolioService.debit(userId, executionTotal);
            } catch (InsufficientFundsException insuf) {
                // Race entre quote/quote-side y el débito. Orden queda como REJECTED.
                // Inconsistencia D17 (acepted MVP): Alpaca paper tiene la posición, BloomTrade no.
                order.markAsRejected("INSUFFICIENT_FUNDS", insuf.getMessage());
                order = orderRepository.save(order);
                eventPublisher.publishEvent(
                        new OrderRejectedEvent(
                                order.getId(),
                                userId,
                                order.getTicker(),
                                order.getQuantity(),
                                "INSUFFICIENT_FUNDS",
                                null));
                throw insuf;
            }

            // Débito exitoso → marcar EXECUTED + upsert posición.
            order.markAsExecuted(alpacaResp.id(), execPrice);
            portfolioService.upsertPosition(
                    userId, order.getTicker(), order.getQuantity(), execPrice);
            order = orderRepository.save(order);

            eventPublisher.publishEvent(
                    new OrderExecutedEvent(
                            order.getId(),
                            userId,
                            order.getTicker(),
                            order.getQuantity(),
                            execPrice,
                            executionTotal,
                            order.getQuotedCommission(),
                            newBalance,
                            alpacaResp.id()));

            return new PlaceOrderResult(true, orderMapper.toResponse(order));
        }

        if (alpacaResp.isRejected()) {
            String reason = alpacaResp.rejectedReason() != null
                    ? alpacaResp.rejectedReason()
                    : "sin razón especificada";
            order.markAsRejected("ALPACA_ORDER_REJECTED", reason);
            order = orderRepository.save(order);
            eventPublisher.publishEvent(
                    new OrderRejectedEvent(
                            order.getId(),
                            userId,
                            order.getTicker(),
                            order.getQuantity(),
                            "ALPACA_ORDER_REJECTED",
                            reason));
            throw new AlpacaOrderRejectedException(reason, alpacaResp.id());
        }

        // 9. No-terminal tras polling — orden encolada en Alpaca (típicamente mercado cerrado).
        //    HU-F09 D29 emergente Lote H.5: no es un fallo. Debitar el cash reservado y dejar
        //    la orden en PENDING vinculada al alpacaOrderId; el fill llegará al abrir el mercado.
        //    Deuda registrada: reconciliation job o webhook de Alpaca actualizará el estado a
        //    EXECUTED + crear la posición cuando finalmente filee (post-MVP).
        log.info(
                "Alpaca aceptó orden pero no llenó en polling sync — encolada: clientOrderId={} alpacaId={} status={}",
                request.clientOrderId(),
                alpacaResp.id(),
                alpacaResp.status());

        BigDecimal newBalance;
        try {
            newBalance = portfolioService.debit(userId, order.getQuotedTotal());
        } catch (InsufficientFundsException insuf) {
            // Race con otra orden — la encolada se rechaza.
            order.markAsRejected("INSUFFICIENT_FUNDS", insuf.getMessage());
            order = orderRepository.save(order);
            eventPublisher.publishEvent(
                    new OrderRejectedEvent(
                            order.getId(),
                            userId,
                            order.getTicker(),
                            order.getQuantity(),
                            "INSUFFICIENT_FUNDS",
                            null));
            throw insuf;
        }

        order.linkToAlpaca(alpacaResp.id());
        order = orderRepository.save(order);
        eventPublisher.publishEvent(
                new OrderQueuedEvent(
                        order.getId(),
                        userId,
                        order.getTicker(),
                        order.getQuantity(),
                        order.getQuotedUnitPrice(),
                        order.getQuotedCommission(),
                        order.getQuotedTotal(),
                        newBalance,
                        alpacaResp.id()));

        return new PlaceOrderResult(true, orderMapper.toResponse(order));
    }

    // ─── helpers privados ────────────────────────────────────────────────────────

    private AlpacaOrderResponse pollUntilTerminal(AlpacaOrderResponse initial) {
        AlpacaOrderResponse current = initial;
        for (int i = 0; i < MAX_POLL_ATTEMPTS && !current.isTerminal(); i++) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AlpacaApiException("Polling interrumpido", 0, e);
            }
            current = alpacaTradingAdapter.getOrder(initial.id());
        }
        return current;
    }

    private void validateTicker(String ticker) {
        if (!AllowedTickers.contains(ticker)) {
            throw new InvalidTickerException(ticker);
        }
    }

    private void validateQuantity(Integer quantity) {
        if (quantity == null || quantity <= 0 || quantity > maxQuantityPerOrder) {
            throw new InvalidQuantityException(
                    "Cantidad fuera de rango [1," + maxQuantityPerOrder + "]: " + quantity);
        }
    }

    private void validateSideForBuy(OrderSide side) {
        if (side == OrderSide.SELL) {
            throw InvalidSideException.sideNotYetImplemented();
        }
    }

    private void validateType(OrderType type) {
        if (type != OrderType.MARKET) {
            throw new InvalidQuantityException("Solo se aceptan órdenes MARKET en MVP");
        }
    }

    private void requireActiveAccount(UUID userId) {
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Usuario no encontrado: " + userId));
        if (user.getEstado() != UserStatus.ACTIVE) {
            throw new AccountNotActiveException(user.getEstado());
        }
    }
}
