package co.edu.unbosque.bloomtrade.trading.service;

import co.edu.unbosque.bloomtrade.admin.service.CommissionManager;
import co.edu.unbosque.bloomtrade.admin.service.MarketScheduleManager;
import co.edu.unbosque.bloomtrade.audit.AuditEvent;
import co.edu.unbosque.bloomtrade.audit.AuditEvent.AuditResult;
import co.edu.unbosque.bloomtrade.audit.AuditEventType;
import co.edu.unbosque.bloomtrade.audit.Auditor;
import co.edu.unbosque.bloomtrade.auth.domain.User;
import co.edu.unbosque.bloomtrade.auth.domain.UserStatus;
import co.edu.unbosque.bloomtrade.auth.exception.AccountNotActiveException;
import co.edu.unbosque.bloomtrade.auth.profile.catalog.AllowedTickers;
import co.edu.unbosque.bloomtrade.auth.profile.exception.InvalidTickerException;
import co.edu.unbosque.bloomtrade.auth.repository.UserRepository;
import co.edu.unbosque.bloomtrade.integration.alpaca.AlpacaApiException;
import co.edu.unbosque.bloomtrade.integration.alpaca.AlpacaOrderNotCancelableException;
import co.edu.unbosque.bloomtrade.integration.alpaca.AlpacaOrderNotFoundException;
import co.edu.unbosque.bloomtrade.integration.alpaca.AlpacaOrderRejectedException;
import co.edu.unbosque.bloomtrade.integration.alpaca.AlpacaTradingAdapter;
import co.edu.unbosque.bloomtrade.integration.alpaca.AlpacaUnexpectedStatusException;
import co.edu.unbosque.bloomtrade.integration.alpaca.MarketDataAdapter;
import co.edu.unbosque.bloomtrade.integration.alpaca.dto.AlpacaOrderResponse;
import co.edu.unbosque.bloomtrade.portfolio.domain.Position;
import co.edu.unbosque.bloomtrade.portfolio.exception.InsufficientFundsException;
import co.edu.unbosque.bloomtrade.portfolio.repository.UserBalanceRepository;
import co.edu.unbosque.bloomtrade.portfolio.service.PortfolioService;
import co.edu.unbosque.bloomtrade.trading.domain.Order;
import co.edu.unbosque.bloomtrade.trading.domain.OrderSide;
import co.edu.unbosque.bloomtrade.trading.domain.OrderStatus;
import co.edu.unbosque.bloomtrade.trading.domain.OrderType;
import co.edu.unbosque.bloomtrade.trading.dto.CancelOutcome;
import co.edu.unbosque.bloomtrade.trading.dto.OrderResponse;
import co.edu.unbosque.bloomtrade.trading.dto.PlaceOrderRequest;
import co.edu.unbosque.bloomtrade.trading.dto.QuoteRequest;
import co.edu.unbosque.bloomtrade.trading.dto.QuoteResponse;
import co.edu.unbosque.bloomtrade.trading.event.OrderCancelPendingEvent;
import co.edu.unbosque.bloomtrade.trading.event.OrderCanceledEvent;
import co.edu.unbosque.bloomtrade.trading.event.OrderExecutedEvent;
import co.edu.unbosque.bloomtrade.trading.event.OrderFailedEvent;
import co.edu.unbosque.bloomtrade.trading.event.OrderQueuedEvent;
import co.edu.unbosque.bloomtrade.trading.event.OrderRejectedEvent;
import co.edu.unbosque.bloomtrade.trading.exception.InsufficientSharesException;
import co.edu.unbosque.bloomtrade.trading.exception.InvalidQuantityException;
import co.edu.unbosque.bloomtrade.trading.exception.OrderNotCancelableException;
import co.edu.unbosque.bloomtrade.trading.exception.OrderNotFoundException;
import co.edu.unbosque.bloomtrade.trading.exception.ShortSellingNotAllowedException;
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
 * Lógica core de HU-F09 + HU-F10 — quote y placeOrder bidireccional (ARCH §3 TradingService).
 *
 * <p>Responsabilidades:
 * <ul>
 *   <li>{@link #quote} — informativo, sin persistencia ni llamadas mutables. Side-aware
 *       (HU-F10): para SELL devuelve {@code sufficientShares}/{@code userShares} y
 *       {@code estimatedTotal} = subtotal − commission (producto neto).</li>
 *   <li>{@link #placeOrder} — entry point que adquiere el lock por {@code clientOrderId} y delega
 *       a {@link #placeOrderTx} vía self-proxy.</li>
 *   <li>{@link #placeOrderTx} — transaccional con dispatch interno por side (HU-F10 D5
 *       D-TRADING-METHOD): validaciones + quote común, luego {@link #handleBuyTx} o
 *       {@link #handleSellTx}.</li>
 * </ul>
 *
 * <p>Decisiones aplicadas: D9 Alpaca-only (data via {@link MarketDataAdapter}), D12 BigDecimal
 * HALF_UP, D13 lock pessimistic, D14 idempotencia por {@code clientOrderId}, D15 events
 * post-commit, D16 estados estrechados. HU-F10 D1 (DELETE en qty=0), D5 (dispatch interno),
 * D9 (SELL queued con decrement optimistic), D11 (positionDeleted/positionResultingQty en event),
 * D12 (lock order), D15 (quote sin lock pessimistic).
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
    private final Auditor auditor;
    private final OrderReconciliationService reconciliationService;
    private final int maxQuantityPerOrder;
    private static final String RESOURCE_ORDERS = "/api/v1/orders";

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
            Auditor auditor,
            OrderReconciliationService reconciliationService,
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
        this.auditor = auditor;
        this.reconciliationService = reconciliationService;
        this.maxQuantityPerOrder = maxQuantityPerOrder;
        this.self = self;
    }

    // ─── quote ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public QuoteResponse quote(UUID userId, QuoteRequest request) {
        validateTicker(request.ticker());
        validateQuantity(request.quantity());
        requireActiveAccount(userId);

        BigDecimal unitPrice = marketDataAdapter.getLatestPrice(request.ticker());
        BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(request.quantity()));
        BigDecimal commission = commissionManager.calculate(INVESTOR_ROLE, subtotal);
        BigDecimal balance = portfolioService.getBalance(userId);
        boolean marketOpen = marketScheduleManager.isOpenNow(request.ticker());

        // HU-F10 — semántica side-aware del quote.
        BigDecimal estimatedTotal;
        boolean sufficientFunds;
        boolean sufficientShares;
        int userShares;

        if (request.side() == OrderSide.SELL) {
            // D15: quote sin lock pessimistic (informativo, no compromete recursos).
            Optional<Position> positionOpt =
                    portfolioService.findPosition(userId, request.ticker());
            userShares = positionOpt.map(Position::getQuantity).orElse(0);
            sufficientShares = userShares >= request.quantity();
            estimatedTotal = subtotal.subtract(commission); // producto neto
            sufficientFunds = true; // vender NUNCA descuenta balance
        } else {
            estimatedTotal = subtotal.add(commission); // lo que se descontará
            sufficientFunds = balance.compareTo(estimatedTotal) >= 0;
            sufficientShares = true; // no aplica para BUY
            userShares = 0; // no aplica para BUY
        }

        return new QuoteResponse(
                request.ticker(),
                request.side(),
                request.quantity(),
                unitPrice.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                subtotal.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                commission.toPlainString(),
                estimatedTotal.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                CURRENCY_USD,
                balance.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                sufficientFunds,
                sufficientShares,
                userShares,
                marketOpen,
                Instant.now());
    }

    // ─── placeOrder ─────────────────────────────────────────────────────────────

    /**
     * Entry point — orquesta el lock + delega la lógica transaccional a {@link #placeOrderTx}
     * vía self-proxy. NO {@code @Transactional} acá: la tx debe arrancar/commitear DENTRO del
     * synchronized para evitar race find→INSERT.
     */
    public PlaceOrderResult placeOrder(UUID userId, PlaceOrderRequest request) {
        Object lock = clientOrderLocks.computeIfAbsent(request.clientOrderId(), k -> new Object());
        synchronized (lock) {
            return self.placeOrderTx(userId, request);
        }
    }

    /**
     * Lógica transaccional con dispatch por side. {@code noRollbackFor} preserva las filas de
     * orden marcadas REJECTED/FAILED a pesar de propagar la excepción al controller (que mapea a
     * 409/422/502). Aplica a las excepciones de validación BUY y SELL: una vez la fila
     * {@code app.orders} se persistió con su error_code, el rollback de la tx la perdería.
     */
    @Transactional(
            noRollbackFor = {
                AlpacaApiException.class,
                AlpacaOrderRejectedException.class,
                InsufficientFundsException.class,
                ShortSellingNotAllowedException.class,
                InsufficientSharesException.class
            })
    public PlaceOrderResult placeOrderTx(UUID userId, PlaceOrderRequest request) {
        // 1. Idempotency check — dentro del lock garantiza visibilidad del INSERT previo
        //    si otro thread con misma key ya pasó por acá.
        Optional<Order> existing = orderRepository.findByClientOrderId(request.clientOrderId());
        if (existing.isPresent()) {
            log.info(
                    "Idempotent request: clientOrderId={} ya existe con id={} status={}",
                    request.clientOrderId(),
                    existing.get().getId(),
                    existing.get().getStatus());
            return new PlaceOrderResult(false, orderMapper.toResponse(existing.get()));
        }

        // 2. Validaciones comunes (sin validateSideForBuy: HU-F10 acepta SELL).
        validateTicker(request.ticker());
        validateQuantity(request.quantity());
        validateType(request.type());
        requireActiveAccount(userId);

        // 3. Get fresh price + calcular subtotal + commission.
        BigDecimal unitPrice = marketDataAdapter.getLatestPrice(request.ticker());
        BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(request.quantity()));
        BigDecimal commission = commissionManager.calculate(INVESTOR_ROLE, subtotal);

        // 4. quotedTotal side-aware (HU-F10):
        //    BUY = subtotal + commission (lo cobrado);
        //    SELL = subtotal − commission (producto neto acreditado).
        BigDecimal quotedTotal =
                (request.side() == OrderSide.BUY)
                        ? subtotal.add(commission)
                        : subtotal.subtract(commission);

        // 5. Validar mercado abierto (stub MVP — siempre true en F09/F10; HU-F14 introducirá la
        //    lógica real con encolamiento. Llamamos isOpenNow para que esté en el call graph.)
        marketScheduleManager.isOpenNow(request.ticker());

        // 6. Dispatch por side (HU-F10 D5).
        if (request.side() == OrderSide.BUY) {
            return handleBuyTx(userId, request, unitPrice, commission, quotedTotal);
        }
        return handleSellTx(userId, request, unitPrice, commission, quotedTotal);
    }

    // ─── handleBuyTx ────────────────────────────────────────────────────────────

    /** Rama BUY del placeOrderTx — heredada intacta de HU-F09 más {@code side=BUY} en eventos. */
    private PlaceOrderResult handleBuyTx(
            UUID userId,
            PlaceOrderRequest request,
            BigDecimal unitPrice,
            BigDecimal commission,
            BigDecimal quotedTotal) {
        // Pre-validación optimista de fondos (SPEC F09 §5.1 paso 13). Evita el INSERT + llamada
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
                            OrderSide.BUY,
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
                            OrderSide.BUY,
                            order.getQuantity(),
                            "ALPACA_API_ERROR",
                            apiEx.getMessage()));
            throw apiEx;
        }

        if (!alpacaResp.isTerminal()) {
            alpacaResp = pollUntilTerminal(alpacaResp);
        }

        if (alpacaResp.isFilled()) {
            BigDecimal execPrice = alpacaResp.filledAvgPrice();
            BigDecimal executionTotal =
                    execPrice
                            .multiply(BigDecimal.valueOf(order.getQuantity()))
                            .add(order.getQuotedCommission());

            BigDecimal newBalance;
            try {
                newBalance = portfolioService.debit(userId, executionTotal);
            } catch (InsufficientFundsException insuf) {
                order.markAsRejected("INSUFFICIENT_FUNDS", insuf.getMessage());
                order = orderRepository.save(order);
                eventPublisher.publishEvent(
                        new OrderRejectedEvent(
                                order.getId(),
                                userId,
                                order.getTicker(),
                                OrderSide.BUY,
                                order.getQuantity(),
                                "INSUFFICIENT_FUNDS",
                                null));
                throw insuf;
            }

            order.markAsExecuted(alpacaResp.id(), execPrice);
            Position updated =
                    portfolioService.upsertPosition(
                            userId, order.getTicker(), order.getQuantity(), execPrice);
            order = orderRepository.save(order);

            eventPublisher.publishEvent(
                    new OrderExecutedEvent(
                            order.getId(),
                            userId,
                            order.getTicker(),
                            OrderSide.BUY,
                            order.getQuantity(),
                            execPrice,
                            executionTotal,
                            order.getQuotedCommission(),
                            newBalance,
                            alpacaResp.id(),
                            updated.getQuantity(),
                            Boolean.FALSE));

            return new PlaceOrderResult(true, orderMapper.toResponse(order));
        }

        if (alpacaResp.isRejected()) {
            String reason =
                    alpacaResp.rejectedReason() != null
                            ? alpacaResp.rejectedReason()
                            : "sin razón especificada";
            order.markAsRejected("ALPACA_ORDER_REJECTED", reason);
            order = orderRepository.save(order);
            eventPublisher.publishEvent(
                    new OrderRejectedEvent(
                            order.getId(),
                            userId,
                            order.getTicker(),
                            OrderSide.BUY,
                            order.getQuantity(),
                            "ALPACA_ORDER_REJECTED",
                            reason));
            throw new AlpacaOrderRejectedException(reason, alpacaResp.id());
        }

        // No-terminal tras polling — BUY queued (D29 F09). Debitar el cash reservado y dejar
        // la orden en PENDING vinculada al alpacaOrderId; el fill llegará al abrir el mercado.
        log.info(
                "Alpaca aceptó BUY pero no llenó en polling sync — encolada: clientOrderId={} alpacaId={} status={}",
                request.clientOrderId(),
                alpacaResp.id(),
                alpacaResp.status());

        BigDecimal newBalance;
        try {
            newBalance = portfolioService.debit(userId, order.getQuotedTotal());
        } catch (InsufficientFundsException insuf) {
            order.markAsRejected("INSUFFICIENT_FUNDS", insuf.getMessage());
            order = orderRepository.save(order);
            eventPublisher.publishEvent(
                    new OrderRejectedEvent(
                            order.getId(),
                            userId,
                            order.getTicker(),
                            OrderSide.BUY,
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
                        OrderSide.BUY,
                        order.getQuantity(),
                        order.getQuotedUnitPrice(),
                        order.getQuotedCommission(),
                        order.getQuotedTotal(),
                        newBalance,
                        alpacaResp.id(),
                        null /* BUY queued: no se toca posición todavía */));

        return new PlaceOrderResult(true, orderMapper.toResponse(order));
    }

    // ─── handleSellTx (HU-F10) ──────────────────────────────────────────────────

    /**
     * Rama SELL del placeOrderTx (HU-F10). Patrón "valida-antes / muta-después":
     * <ol>
     *   <li>{@link PortfolioService#validateSellable} adquiere lock pessimistic sobre la fila
     *       de {@code app.positions}, lanza {@link ShortSellingNotAllowedException} o
     *       {@link InsufficientSharesException} si no se puede vender. El lock se retiene en
     *       la transacción del caller — el subsiguiente {@code decrementPosition} lo reutiliza.</li>
     *   <li>INSERT order PENDING.</li>
     *   <li>Submit Alpaca con {@code side=sell}.</li>
     *   <li>Si {@code filled}: decrementar position (DELETE si qty resultante = 0) + credit
     *       balance por producto neto + publish {@link OrderExecutedEvent} con {@code side=SELL}
     *       + positionResultingQty + positionDeleted.</li>
     *   <li>Si {@code rejected}: no se decrementa ni acredita (posición y balance intactos).</li>
     *   <li>Si {@code accepted} no-terminal (D9 D-SELL-QUEUED-RISK): decrementar posición
     *       optimistamente, NO acreditar balance (no sabemos execPrice real). Reconciliación
     *       post-MVP actualizará el balance al fill.</li>
     * </ol>
     */
    private PlaceOrderResult handleSellTx(
            UUID userId,
            PlaceOrderRequest request,
            BigDecimal unitPrice,
            BigDecimal commission,
            BigDecimal quotedTotal) {
        // 1. Pre-validar con lock — lanza 409 SHORT_SELLING_NOT_ALLOWED / INSUFFICIENT_SHARES.
        portfolioService.validateSellable(userId, request.ticker(), request.quantity());

        // 2. INSERT order PENDING.
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

        // 3. Submit Alpaca con side="sell" (AlpacaTradingAdapter mapea OrderSide.SELL).
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
                            OrderSide.SELL,
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
                            OrderSide.SELL,
                            order.getQuantity(),
                            "ALPACA_API_ERROR",
                            apiEx.getMessage()));
            throw apiEx;
        }

        if (!alpacaResp.isTerminal()) {
            alpacaResp = pollUntilTerminal(alpacaResp);
        }

        // 4. Filled — mutación atómica: decrement + markExecuted + credit.
        if (alpacaResp.isFilled()) {
            BigDecimal execPrice = alpacaResp.filledAvgPrice();

            // markAsExecuted es side-aware: SELL calcula executionTotal = subtotal − commission.
            order.markAsExecuted(alpacaResp.id(), execPrice);
            BigDecimal executionTotal = order.getExecutionTotal();

            Optional<Position> updated =
                    portfolioService.decrementPosition(
                            userId, order.getTicker(), order.getQuantity());

            BigDecimal newBalance = portfolioService.credit(userId, executionTotal);

            order = orderRepository.save(order);

            eventPublisher.publishEvent(
                    new OrderExecutedEvent(
                            order.getId(),
                            userId,
                            order.getTicker(),
                            OrderSide.SELL,
                            order.getQuantity(),
                            execPrice,
                            executionTotal,
                            order.getQuotedCommission(),
                            newBalance,
                            alpacaResp.id(),
                            updated.map(Position::getQuantity).orElse(0),
                            updated.isEmpty()));

            return new PlaceOrderResult(true, orderMapper.toResponse(order));
        }

        // 5. Rejected — posición y balance intactos.
        if (alpacaResp.isRejected()) {
            String reason =
                    alpacaResp.rejectedReason() != null
                            ? alpacaResp.rejectedReason()
                            : "sin razón especificada";
            order.markAsRejected("ALPACA_ORDER_REJECTED", reason);
            order = orderRepository.save(order);
            eventPublisher.publishEvent(
                    new OrderRejectedEvent(
                            order.getId(),
                            userId,
                            order.getTicker(),
                            OrderSide.SELL,
                            order.getQuantity(),
                            "ALPACA_ORDER_REJECTED",
                            reason));
            throw new AlpacaOrderRejectedException(reason, alpacaResp.id());
        }

        // 6. No-terminal — SELL queued (D9). Decrementar posición optimistamente, NO acreditar
        //    balance (precio de fill desconocido). Email + audit "se encoló, recibirás crédito
        //    al ejecutarse"; reconciliación post-MVP actualizará el balance al fill.
        log.info(
                "Alpaca aceptó SELL pero no llenó en polling sync — encolada: clientOrderId={} alpacaId={} status={}",
                request.clientOrderId(),
                alpacaResp.id(),
                alpacaResp.status());

        Optional<Position> updated =
                portfolioService.decrementPosition(
                        userId, order.getTicker(), order.getQuantity());

        order.linkToAlpaca(alpacaResp.id());
        order = orderRepository.save(order);

        // Balance ACTUAL (sin cambios — la venta no acreditó nada todavía).
        BigDecimal currentBalance = portfolioService.getBalance(userId);

        eventPublisher.publishEvent(
                new OrderQueuedEvent(
                        order.getId(),
                        userId,
                        order.getTicker(),
                        OrderSide.SELL,
                        order.getQuantity(),
                        order.getQuotedUnitPrice(),
                        order.getQuotedCommission(),
                        order.getQuotedTotal(),
                        currentBalance,
                        alpacaResp.id(),
                        updated.map(Position::getQuantity).orElse(0)));

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

    // ─── HU-F15 — cancelOrder ──────────────────────────────────────────────────

    /**
     * Cancela una orden Market en estado {@code PENDING + alpaca_order_id != null} (HU-F15).
     *
     * <p>Flujo:
     * <ol>
     *   <li>Lock canónico balances→positions (D17 F10) — adquirido por
     *       {@code findByUserIdForUpdate} aunque la operación pueda solo tocar positions (SELL).</li>
     *   <li>Lookup defensivo {@link OrderRepository#findByIdAndUserId} — 404 si no es del user.</li>
     *   <li>Short-circuit idempotency: si ya CANCELED o ya tiene cancel_requested_at, retorna
     *       la orden actual sin tocar Alpaca (D7).</li>
     *   <li>Si status no cancelable (EXECUTED/REJECTED/FAILED/EXPIRED): 409 + audit
     *       ORDER_CANCEL_REJECTED.</li>
     *   <li>Llama {@link AlpacaTradingAdapter#cancelOrder} — polling 2s.</li>
     *   <li>Switch outcome:
     *     <ul>
     *       <li>{@code Canceled}: marcar CANCELED + refund/restore + publish OrderCanceledEvent.</li>
     *       <li>{@code PendingCancel}: marcar cancel_requested_at + publish OrderCancelPendingEvent.</li>
     *       <li>{@code RaceFilled}: tratar como EXECUTED late-arrival (D17 D-RACE-FILLED-UX).</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * <p>{@code noRollbackFor}: preserva las filas de audit/orden a pesar de propagar la
     * excepción al controller. Aplica a {@code OrderNotFoundException}, {@code OrderNotCancelableException},
     * {@code AlpacaApiException} (502 BROKER_UNAVAILABLE).
     */
    @Transactional(
            noRollbackFor = {
                OrderNotFoundException.class,
                OrderNotCancelableException.class,
                AlpacaApiException.class
            })
    public OrderResponse cancelOrder(UUID userId, UUID orderId) {
        // 1. Lock canónico balances first (D17 F10).
        userBalanceRepository
                .findByUserIdForUpdate(userId)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Balance no encontrado para userId=" + userId));

        // 2. Lookup defensivo (404 if cross-user or no existe).
        Order order =
                orderRepository
                        .findByIdAndUserId(orderId, userId)
                        .orElseThrow(() -> new OrderNotFoundException(orderId));

        // 3. Idempotency short-circuit.
        if (order.getStatus() == OrderStatus.CANCELED) {
            log.info(
                    "Idempotent cancel request: order {} ya CANCELED at {}",
                    orderId,
                    order.getCanceledAt());
            auditor.record(
                    AuditEvent.builder()
                            .eventType(AuditEventType.ORDER_DUPLICATE_CANCEL_REQUEST)
                            .resource(RESOURCE_ORDERS)
                            .result(AuditResult.ALLOWED)
                            .actorId(userId.toString())
                            .orderId(orderId.toString())
                            .detail("existingStatus", "CANCELED")
                            .detail("alreadyCanceledAt", String.valueOf(order.getCanceledAt()))
                            .build());
            return orderMapper.toResponse(order);
        }
        if (order.getCancelRequestedAt() != null) {
            log.info(
                    "Idempotent cancel request: order {} ya tiene cancel_requested_at at {}",
                    orderId,
                    order.getCancelRequestedAt());
            auditor.record(
                    AuditEvent.builder()
                            .eventType(AuditEventType.ORDER_DUPLICATE_CANCEL_REQUEST)
                            .resource(RESOURCE_ORDERS)
                            .result(AuditResult.ALLOWED)
                            .actorId(userId.toString())
                            .orderId(orderId.toString())
                            .detail("existingStatus", "PENDING")
                            .detail("cancelRequestedAt", String.valueOf(order.getCancelRequestedAt()))
                            .build());
            return orderMapper.toResponse(order);
        }
        if (!order.isCancelable()) {
            auditor.record(
                    AuditEvent.builder()
                            .eventType(AuditEventType.ORDER_CANCEL_REJECTED)
                            .resource(RESOURCE_ORDERS)
                            .result(AuditResult.DENIED)
                            .actorId(userId.toString())
                            .orderId(orderId.toString())
                            .detail("currentStatus", order.getStatus().name())
                            .detail("reason", "NOT_CANCELABLE")
                            .build());
            throw new OrderNotCancelableException(orderId, order.getStatus());
        }

        // 4. Audit ORDER_CANCEL_REQUESTED ANTES del DELETE Alpaca (preserva audit en caso 502).
        auditor.record(
                AuditEvent.builder()
                        .eventType(AuditEventType.ORDER_CANCEL_REQUESTED)
                        .resource(RESOURCE_ORDERS)
                        .result(AuditResult.ALLOWED)
                        .actorId(userId.toString())
                        .orderId(orderId.toString())
                        .detail("alpacaOrderId", order.getAlpacaOrderId())
                        .detail("side", order.getSide().name())
                        .detail("ticker", order.getTicker())
                        .detail("quantity", order.getQuantity())
                        .detail("quotedTotal", order.getQuotedTotal().toPlainString())
                        .build());

        // 5. Llama Alpaca cancel.
        CancelOutcome outcome;
        try {
            outcome = alpacaTradingAdapter.cancelOrder(order.getAlpacaOrderId());
        } catch (AlpacaOrderNotFoundException | AlpacaOrderNotCancelableException drift) {
            // Drift detectado: Alpaca dice "no existe" o "no cancelable". Reconcile lazy v2
            // inline: fetch estado real + materializar transición correspondiente (Lote C).
            log.warn(
                    "Drift detected al cancelar order {}: {} — reconcile inline",
                    orderId,
                    drift.getMessage());
            auditor.record(
                    AuditEvent.builder()
                            .eventType(AuditEventType.ORDER_CANCEL_REQUESTED)
                            .resource(RESOURCE_ORDERS)
                            .result(AuditResult.ALLOWED)
                            .actorId(userId.toString())
                            .orderId(orderId.toString())
                            .detail("outcome", "DRIFT_RECONCILE")
                            .detail("driftType", drift.getClass().getSimpleName())
                            .build());
            try {
                Order reconciled = reconciliationService.applyDriftReconcile(order);
                BigDecimal refundedAmount =
                        reconciled.getSide() == OrderSide.BUY
                                        && (reconciled.getStatus() == OrderStatus.CANCELED
                                                || reconciled.getStatus() == OrderStatus.EXPIRED
                                                || reconciled.getStatus() == OrderStatus.REJECTED)
                                ? reconciled.getQuotedTotal()
                                : null;
                Integer restoredQty =
                        reconciled.getSide() == OrderSide.SELL
                                        && (reconciled.getStatus() == OrderStatus.CANCELED
                                                || reconciled.getStatus() == OrderStatus.EXPIRED
                                                || reconciled.getStatus() == OrderStatus.REJECTED)
                                ? reconciled.getQuantity()
                                : null;
                return orderMapper.toResponseWithRefund(reconciled, refundedAmount, restoredQty);
            } catch (IllegalStateException unexpected) {
                // Estado Alpaca no-terminal en drift (raro). Tratar como BROKER_UNAVAILABLE.
                log.error(
                        "Drift reconcile inline falló para order {}: {}",
                        orderId,
                        unexpected.getMessage());
                auditor.record(
                        AuditEvent.builder()
                                .eventType(AuditEventType.ORDER_CANCEL_FAILED)
                                .resource(RESOURCE_ORDERS)
                                .result(AuditResult.DENIED)
                                .actorId(userId.toString())
                                .orderId(orderId.toString())
                                .detail("reason", "DRIFT_UNEXPECTED_STATE")
                                .detail("errorMessage", unexpected.getMessage())
                                .build());
                throw new AlpacaApiException(
                        "Drift reconcile inline falló: " + unexpected.getMessage(), 1, unexpected);
            }
        } catch (AlpacaUnexpectedStatusException unexpected) {
            log.error(
                    "Status inesperado al cancelar order {}: {}",
                    orderId,
                    unexpected.getMessage());
            auditor.record(
                    AuditEvent.builder()
                            .eventType(AuditEventType.ORDER_CANCEL_FAILED)
                            .resource(RESOURCE_ORDERS)
                            .result(AuditResult.DENIED)
                            .actorId(userId.toString())
                            .orderId(orderId.toString())
                            .detail("reason", "UNEXPECTED_STATUS")
                            .detail("unexpectedStatus", unexpected.getUnexpectedStatus())
                            .build());
            throw new AlpacaApiException(
                    "Status inesperado de Alpaca: " + unexpected.getUnexpectedStatus(),
                    1,
                    unexpected);
        } catch (AlpacaApiException apiEx) {
            log.error("Alpaca down al cancelar order {}: {}", orderId, apiEx.getMessage());
            auditor.record(
                    AuditEvent.builder()
                            .eventType(AuditEventType.ORDER_CANCEL_FAILED)
                            .resource(RESOURCE_ORDERS)
                            .result(AuditResult.DENIED)
                            .actorId(userId.toString())
                            .orderId(orderId.toString())
                            .detail("reason", "BROKER_UNAVAILABLE")
                            .detail("attempts", apiEx.getAttempts())
                            .build());
            throw apiEx;
        }

        // 6. Switch outcome.
        return switch (outcome) {
            case CancelOutcome.Canceled c ->
                    applyCanceledTransition(order, c, OrderCanceledEvent.CancelSource.USER_REQUEST);
            case CancelOutcome.PendingCancel ignored -> applyPendingCancelTransition(order);
            case CancelOutcome.RaceFilled r -> applyRaceFilledTransition(order, r);
        };
    }

    /** Aplica transición CANCELED + reverse de balance (BUY) o position (SELL) + publish event. */
    private OrderResponse applyCanceledTransition(
            Order order,
            CancelOutcome.Canceled canceled,
            OrderCanceledEvent.CancelSource source) {
        BigDecimal refundedAmount = null;
        Integer restoredQty = null;

        if (order.getSide() == OrderSide.BUY) {
            // Refund: revertir el debit optimista del placeOrder queued (D29 F09).
            refundedAmount = order.getQuotedTotal();
            portfolioService.credit(order.getUserId(), refundedAmount);
        } else {
            // Restore: re-INSERT o increment de la posición (D-SELL-QUEUED-RISK F10).
            // D13 D-RESTORE-AVG-BUY-PRICE: usar snapshot del avg_buy_price si existe; fallback
            // a quoted_unit_price para SELLs legacy pre-V6.
            restoredQty = order.getQuantity();
            BigDecimal avgBuyPrice = order.getAvgBuyPriceAtSubmission();
            if (avgBuyPrice == null) {
                avgBuyPrice = order.getQuotedUnitPrice();
                log.warn(
                        "SELL legacy sin avg_buy_price_at_submission — usando quoted_unit_price como fallback: orderId={}",
                        order.getId());
            }
            portfolioService.upsertPosition(
                    order.getUserId(), order.getTicker(), restoredQty, avgBuyPrice);
        }

        order.markAsCanceled();
        order = orderRepository.save(order);

        eventPublisher.publishEvent(
                new OrderCanceledEvent(
                        order.getId(),
                        order.getUserId(),
                        order.getTicker(),
                        order.getSide(),
                        order.getQuantity(),
                        order.getAlpacaOrderId(),
                        order.getQuotedTotal(),
                        refundedAmount,
                        restoredQty,
                        order.getCanceledAt(),
                        source));

        return orderMapper.toResponseWithRefund(order, refundedAmount, restoredQty);
    }

    /** Polling-timeout: marca cancel_requested_at, publish info-only event. */
    private OrderResponse applyPendingCancelTransition(Order order) {
        order.markCancelRequested();
        order = orderRepository.save(order);

        eventPublisher.publishEvent(
                new OrderCancelPendingEvent(
                        order.getId(),
                        order.getUserId(),
                        order.getTicker(),
                        order.getSide(),
                        order.getQuantity(),
                        order.getAlpacaOrderId(),
                        order.getCancelRequestedAt()));

        log.info(
                "Cancel polling-timeout para order {} — reconcile lazy v2 materializará",
                order.getId());
        return orderMapper.toResponse(order);
    }

    /**
     * RACE_FILLED: Alpaca llenó la orden durante el polling de cancel. Trata como EXECUTED
     * late-arrival (D17 D-RACE-FILLED-UX). Para BUY ajusta balance por delta vs quotedTotal;
     * para SELL acredita el execution_total real (posición ya estaba decrementada en queued).
     */
    private OrderResponse applyRaceFilledTransition(
            Order order, CancelOutcome.RaceFilled race) {
        BigDecimal execPrice = race.filledAvgPrice();
        order.markAsExecuted(order.getAlpacaOrderId(), execPrice);
        BigDecimal executionTotal = order.getExecutionTotal();

        BigDecimal newBalance;
        if (order.getSide() == OrderSide.BUY) {
            // BUY queued: balance debitado por quotedTotal. Ajustar por delta vs execution_total real.
            BigDecimal delta = executionTotal.subtract(order.getQuotedTotal());
            if (delta.signum() > 0) {
                try {
                    newBalance = portfolioService.debit(order.getUserId(), delta);
                } catch (InsufficientFundsException insuf) {
                    // Edge raro: el delta excede el balance restante. Log y propagar como ALPACA_API.
                    log.error(
                            "RACE_FILLED BUY delta debit failed para order {}: {}",
                            order.getId(),
                            insuf.getMessage());
                    throw new AlpacaApiException(
                            "RACE_FILLED: fondos insuficientes para slippage delta", 1, insuf);
                }
            } else if (delta.signum() < 0) {
                newBalance = portfolioService.credit(order.getUserId(), delta.negate());
            } else {
                newBalance = portfolioService.getBalance(order.getUserId());
            }
        } else {
            // SELL queued: balance no se había acreditado (D-SELL-QUEUED-RISK F10). Acreditar ahora.
            newBalance = portfolioService.credit(order.getUserId(), executionTotal);
        }

        order = orderRepository.save(order);

        eventPublisher.publishEvent(
                new OrderExecutedEvent(
                        order.getId(),
                        order.getUserId(),
                        order.getTicker(),
                        order.getSide(),
                        order.getQuantity(),
                        execPrice,
                        executionTotal,
                        order.getQuotedCommission(),
                        newBalance,
                        order.getAlpacaOrderId(),
                        null,
                        Boolean.FALSE));

        log.warn(
                "RACE_FILLED para order {}: la orden se ejecutó durante el cancel polling",
                order.getId());
        return orderMapper.toResponse(order);
    }

}
