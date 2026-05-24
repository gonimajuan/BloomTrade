package co.edu.unbosque.bloomtrade.trading.event;

import co.edu.unbosque.bloomtrade.audit.AuditEvent;
import co.edu.unbosque.bloomtrade.audit.AuditEvent.AuditResult;
import co.edu.unbosque.bloomtrade.audit.AuditEventType;
import co.edu.unbosque.bloomtrade.audit.Auditor;
import co.edu.unbosque.bloomtrade.auth.domain.User;
import co.edu.unbosque.bloomtrade.auth.repository.UserRepository;
import co.edu.unbosque.bloomtrade.notification.Notifier;
import co.edu.unbosque.bloomtrade.notification.dto.OrderExecutedEmailCommand;
import co.edu.unbosque.bloomtrade.notification.dto.OrderFailedEmailCommand;
import co.edu.unbosque.bloomtrade.notification.dto.OrderQueuedEmailCommand;
import co.edu.unbosque.bloomtrade.notification.dto.OrderRejectedEmailCommand;
import co.edu.unbosque.bloomtrade.trading.domain.OrderSide;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Listener post-commit de los 4 eventos de orden (HU-F09 D15, extendido HU-F10). Dispara los
 * efectos colaterales (email + audit log) SIN bloquear la transacción del placeOrder ni
 * participar de su rollback.
 *
 * <p>{@code @TransactionalEventListener(phase=AFTER_COMMIT)} garantiza que un rollback del
 * placeOrder NO dispara el listener; los eventos publicados en una tx rollbackeada se descartan
 * silenciosamente. Esto es deseable: si el commit falla, el usuario nunca debió recibir el
 * email "tu orden fue ejecutada".
 *
 * <p>{@code @Transactional(REQUIRES_NEW, readOnly = true)} sobre cada método: cuando el
 * listener corre, ya NO hay tx activa (post-commit del caller). Necesitamos abrir una nueva
 * para hacer el lookup del {@link User} (email + nombreCompleto). El email send es {@code @Async}
 * en {@link Notifier} — se dispatcha a otro thread sin esperar.
 *
 * <p>Decisión SPEC F09 §9.2 + F10 §9.2: {@code OrderRejectedEvent} con
 * {@code reason ∈ {INSUFFICIENT_FUNDS, SHORT_SELLING_NOT_ALLOWED, INSUFFICIENT_SHARES}} NO
 * envía email — el usuario ya vio el error en pantalla (response 409). Sí emite el audit log.
 *
 * <p><b>HU-F10 Lote B — emails SELL provisionalmente skipped</b>: los métodos del {@link Notifier}
 * son BUY-only hasta Lote C (D7 D-NOTIFIER-SPLIT renombra a {@code *Buy} y agrega {@code *Sell}).
 * Mientras tanto, eventos con {@code side=SELL} emiten audit log con detalles enriquecidos pero
 * el envío del email queda como log {@code warn}. El comportamiento E2E SELL completo (audit +
 * email) se valida en HITO 5 tras Lote C.
 */
@Component
public class OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);
    private static final String RESOURCE = "/api/v1/orders";
    private static final String REASON_INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";
    private static final String REASON_SHORT_SELLING = "SHORT_SELLING_NOT_ALLOWED";
    private static final String REASON_INSUFFICIENT_SHARES = "INSUFFICIENT_SHARES";

    private final Auditor auditor;
    private final Notifier notifier;
    private final UserRepository userRepository;

    public OrderEventListener(Auditor auditor, Notifier notifier, UserRepository userRepository) {
        this.auditor = auditor;
        this.notifier = notifier;
        this.userRepository = userRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onOrderExecuted(OrderExecutedEvent event) {
        String userId = event.userId().toString();
        String orderId = event.orderId().toString();

        // Audit ORDER_CREATED + ORDER_EXECUTED juntos: la orden pasó de no-existir a EXECUTED
        // en una sola transacción del placeOrder. Emitir ambos da el rastro completo en Kibana
        // sin tener que reconstruirlo desde el código.
        auditor.record(
                AuditEvent.builder()
                        .eventType(AuditEventType.ORDER_CREATED)
                        .resource(RESOURCE)
                        .result(AuditResult.ALLOWED)
                        .actorId(userId)
                        .orderId(orderId)
                        .detail("ticker", event.ticker())
                        .detail("side", event.side().name())
                        .detail("quantity", event.quantity())
                        .build());

        AuditEvent.Builder executedBuilder =
                AuditEvent.builder()
                        .eventType(AuditEventType.ORDER_EXECUTED)
                        .resource(RESOURCE)
                        .result(AuditResult.ALLOWED)
                        .actorId(userId)
                        .orderId(orderId)
                        .detail("ticker", event.ticker())
                        .detail("side", event.side().name())
                        .detail("quantity", event.quantity())
                        .detail("executionUnitPrice", event.executionUnitPrice().toPlainString())
                        .detail("executionTotal", event.executionTotal().toPlainString())
                        .detail("commission", event.commission().toPlainString())
                        .detail("alpacaOrderId", event.alpacaOrderId());
        if (event.positionResultingQty() != null) {
            executedBuilder.detail("positionResultingQty", event.positionResultingQty());
        }
        if (event.positionDeleted() != null && event.positionDeleted()) {
            executedBuilder.detail("positionDeleted", Boolean.TRUE);
        }
        auditor.record(executedBuilder.build());

        Optional<User> userOpt = userRepository.findById(event.userId());
        if (userOpt.isEmpty()) {
            log.warn("Usuario no encontrado para email order-executed: userId={}", userId);
            return;
        }
        User user = userOpt.get();
        OrderExecutedEmailCommand command =
                new OrderExecutedEmailCommand(
                        userId,
                        user.getEmail(),
                        user.getNombreCompleto(),
                        event.ticker(),
                        event.quantity(),
                        event.executionUnitPrice(),
                        event.executionTotal(),
                        event.commission(),
                        event.newBalance(),
                        event.positionResultingQty(),
                        event.positionDeleted());

        // HU-F10 D7: dispatch por side al método correcto del Notifier.
        if (event.side() == OrderSide.SELL) {
            notifier.sendOrderExecutedEmailSell(command);
        } else {
            notifier.sendOrderExecutedEmailBuy(command);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onOrderRejected(OrderRejectedEvent event) {
        String userId = event.userId().toString();
        String orderId = event.orderId().toString();

        auditor.record(
                AuditEvent.builder()
                        .eventType(AuditEventType.ORDER_REJECTED)
                        .resource(RESOURCE)
                        .result(AuditResult.DENIED)
                        .actorId(userId)
                        .orderId(orderId)
                        .detail("ticker", event.ticker())
                        .detail("side", event.side().name())
                        .detail("quantity", event.quantity())
                        .detail("reason", event.reason())
                        .detail("alpacaReason", event.alpacaReason())
                        .build());

        // SPEC F09 §9.2 + F10 §9.2: NO enviar email para errores visibles en pantalla
        // (INSUFFICIENT_FUNDS, SHORT_SELLING_NOT_ALLOWED, INSUFFICIENT_SHARES).
        if (REASON_INSUFFICIENT_FUNDS.equals(event.reason())
                || REASON_SHORT_SELLING.equals(event.reason())
                || REASON_INSUFFICIENT_SHARES.equals(event.reason())) {
            log.debug(
                    "Skipping rejected email for visible-on-screen error: orderId={} reason={}",
                    orderId,
                    event.reason());
            return;
        }

        Optional<User> userOpt = userRepository.findById(event.userId());
        if (userOpt.isEmpty()) {
            log.warn("Usuario no encontrado para email order-rejected: userId={}", userId);
            return;
        }
        User user = userOpt.get();
        OrderRejectedEmailCommand command =
                new OrderRejectedEmailCommand(
                        userId,
                        user.getEmail(),
                        user.getNombreCompleto(),
                        event.ticker(),
                        event.quantity(),
                        event.alpacaReason() != null ? event.alpacaReason() : "sin razón especificada");

        if (event.side() == OrderSide.SELL) {
            notifier.sendOrderRejectedEmailSell(command);
        } else {
            notifier.sendOrderRejectedEmailBuy(command);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onOrderFailed(OrderFailedEvent event) {
        String userId = event.userId().toString();
        String orderId = event.orderId().toString();

        auditor.record(
                AuditEvent.builder()
                        .eventType(AuditEventType.ORDER_FAILED)
                        .resource(RESOURCE)
                        .result(AuditResult.DENIED)
                        .actorId(userId)
                        .orderId(orderId)
                        .detail("ticker", event.ticker())
                        .detail("side", event.side().name())
                        .detail("quantity", event.quantity())
                        .detail("errorCode", event.errorCode())
                        .detail("errorMessage", event.errorMessage())
                        .build());

        Optional<User> userOpt = userRepository.findById(event.userId());
        if (userOpt.isEmpty()) {
            log.warn("Usuario no encontrado para email order-failed: userId={}", userId);
            return;
        }
        User user = userOpt.get();
        OrderFailedEmailCommand command =
                new OrderFailedEmailCommand(
                        userId,
                        user.getEmail(),
                        user.getNombreCompleto(),
                        event.ticker(),
                        event.quantity(),
                        event.errorMessage() != null ? event.errorMessage() : "error técnico");

        if (event.side() == OrderSide.SELL) {
            notifier.sendOrderFailedEmailSell(command);
        } else {
            notifier.sendOrderFailedEmailBuy(command);
        }
    }

    /**
     * Handler de {@link OrderQueuedEvent} — orden aceptada por Alpaca pero NO ejecutada en la
     * ventana de polling (típicamente mercado cerrado). HU-F09 D29 emergente Lote H.5;
     * HU-F10 D9 extendido a SELL.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onOrderQueued(OrderQueuedEvent event) {
        String userId = event.userId().toString();
        String orderId = event.orderId().toString();

        // Audit ORDER_CREATED + ORDER_QUEUED juntos.
        auditor.record(
                AuditEvent.builder()
                        .eventType(AuditEventType.ORDER_CREATED)
                        .resource(RESOURCE)
                        .result(AuditResult.ALLOWED)
                        .actorId(userId)
                        .orderId(orderId)
                        .detail("ticker", event.ticker())
                        .detail("side", event.side().name())
                        .detail("quantity", event.quantity())
                        .build());

        AuditEvent.Builder queuedBuilder =
                AuditEvent.builder()
                        .eventType(AuditEventType.ORDER_QUEUED)
                        .resource(RESOURCE)
                        .result(AuditResult.ALLOWED)
                        .actorId(userId)
                        .orderId(orderId)
                        .detail("ticker", event.ticker())
                        .detail("side", event.side().name())
                        .detail("quantity", event.quantity())
                        .detail("quotedTotal", event.quotedTotal().toPlainString())
                        .detail("alpacaOrderId", event.alpacaOrderId());
        if (event.positionResultingQty() != null) {
            queuedBuilder.detail("positionResultingQty", event.positionResultingQty());
        }
        auditor.record(queuedBuilder.build());

        Optional<User> userOpt = userRepository.findById(event.userId());
        if (userOpt.isEmpty()) {
            log.warn("Usuario no encontrado para email order-queued: userId={}", userId);
            return;
        }
        User user = userOpt.get();
        OrderQueuedEmailCommand command =
                new OrderQueuedEmailCommand(
                        userId,
                        user.getEmail(),
                        user.getNombreCompleto(),
                        event.ticker(),
                        event.quantity(),
                        event.quotedUnitPrice(),
                        event.quotedTotal(),
                        event.commission(),
                        event.newBalance(),
                        event.alpacaOrderId(),
                        event.positionResultingQty());

        if (event.side() == OrderSide.SELL) {
            notifier.sendOrderQueuedEmailSell(command);
        } else {
            notifier.sendOrderQueuedEmailBuy(command);
        }
    }
}
