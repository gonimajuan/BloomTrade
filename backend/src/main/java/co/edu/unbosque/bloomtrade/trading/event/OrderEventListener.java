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
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Listener post-commit de los 3 eventos de orden (HU-F09 D15). Dispara los efectos colaterales
 * (email + audit log) SIN bloquear la transacción del placeOrder ni participar de su rollback.
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
 * <p>Decisión SPEC §9.2: {@code OrderRejectedEvent} con {@code reason=INSUFFICIENT_FUNDS} NO
 * envía email — el usuario ya vio el error en pantalla (response 409). Sí emite el audit log.
 */
@Component
public class OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);
    private static final String RESOURCE = "/api/v1/orders";
    private static final String REASON_INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";

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
                        .detail("quantity", event.quantity())
                        .build());
        auditor.record(
                AuditEvent.builder()
                        .eventType(AuditEventType.ORDER_EXECUTED)
                        .resource(RESOURCE)
                        .result(AuditResult.ALLOWED)
                        .actorId(userId)
                        .orderId(orderId)
                        .detail("ticker", event.ticker())
                        .detail("quantity", event.quantity())
                        .detail("executionUnitPrice", event.executionUnitPrice().toPlainString())
                        .detail("executionTotal", event.executionTotal().toPlainString())
                        .detail("commission", event.commission().toPlainString())
                        .detail("alpacaOrderId", event.alpacaOrderId())
                        .build());

        Optional<User> userOpt = userRepository.findById(event.userId());
        if (userOpt.isEmpty()) {
            log.warn("Usuario no encontrado para email order-executed: userId={}", userId);
            return;
        }
        User user = userOpt.get();
        notifier.sendOrderExecutedEmail(
                new OrderExecutedEmailCommand(
                        userId,
                        user.getEmail(),
                        user.getNombreCompleto(),
                        event.ticker(),
                        event.quantity(),
                        event.executionUnitPrice(),
                        event.executionTotal(),
                        event.commission(),
                        event.newBalance()));
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
                        .detail("quantity", event.quantity())
                        .detail("reason", event.reason())
                        .detail("alpacaReason", event.alpacaReason())
                        .build());

        // SPEC §9.2: NO enviar email para INSUFFICIENT_FUNDS — el usuario ya lo vio en pantalla.
        if (REASON_INSUFFICIENT_FUNDS.equals(event.reason())) {
            log.debug("Skipping rejected email for INSUFFICIENT_FUNDS: orderId={}", orderId);
            return;
        }

        Optional<User> userOpt = userRepository.findById(event.userId());
        if (userOpt.isEmpty()) {
            log.warn("Usuario no encontrado para email order-rejected: userId={}", userId);
            return;
        }
        User user = userOpt.get();
        notifier.sendOrderRejectedEmail(
                new OrderRejectedEmailCommand(
                        userId,
                        user.getEmail(),
                        user.getNombreCompleto(),
                        event.ticker(),
                        event.quantity(),
                        event.alpacaReason() != null ? event.alpacaReason() : "sin razón especificada"));
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
        notifier.sendOrderFailedEmail(
                new OrderFailedEmailCommand(
                        userId,
                        user.getEmail(),
                        user.getNombreCompleto(),
                        event.ticker(),
                        event.quantity(),
                        event.errorMessage() != null ? event.errorMessage() : "error técnico"));
    }

    /**
     * Handler de {@link OrderQueuedEvent} — orden aceptada por Alpaca pero NO ejecutada en la
     * ventana de polling (típicamente mercado cerrado). HU-F09 D29 emergente Lote H.5.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onOrderQueued(OrderQueuedEvent event) {
        String userId = event.userId().toString();
        String orderId = event.orderId().toString();

        // Audit ORDER_CREATED + ORDER_QUEUED juntos: la orden pasó de no-existir a PENDING
        // (encolada en Alpaca) en una sola transacción del placeOrder.
        auditor.record(
                AuditEvent.builder()
                        .eventType(AuditEventType.ORDER_CREATED)
                        .resource(RESOURCE)
                        .result(AuditResult.ALLOWED)
                        .actorId(userId)
                        .orderId(orderId)
                        .detail("ticker", event.ticker())
                        .detail("quantity", event.quantity())
                        .build());
        auditor.record(
                AuditEvent.builder()
                        .eventType(AuditEventType.ORDER_QUEUED)
                        .resource(RESOURCE)
                        .result(AuditResult.ALLOWED)
                        .actorId(userId)
                        .orderId(orderId)
                        .detail("ticker", event.ticker())
                        .detail("quantity", event.quantity())
                        .detail("quotedTotal", event.quotedTotal().toPlainString())
                        .detail("alpacaOrderId", event.alpacaOrderId())
                        .build());

        Optional<User> userOpt = userRepository.findById(event.userId());
        if (userOpt.isEmpty()) {
            log.warn("Usuario no encontrado para email order-queued: userId={}", userId);
            return;
        }
        User user = userOpt.get();
        notifier.sendOrderQueuedEmail(
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
                        event.alpacaOrderId()));
    }
}
