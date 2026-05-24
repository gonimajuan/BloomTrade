package co.edu.unbosque.bloomtrade.trading.event;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import co.edu.unbosque.bloomtrade.audit.AuditEvent;
import co.edu.unbosque.bloomtrade.audit.AuditEventType;
import co.edu.unbosque.bloomtrade.audit.Auditor;
import co.edu.unbosque.bloomtrade.auth.domain.DocumentType;
import co.edu.unbosque.bloomtrade.auth.domain.User;
import co.edu.unbosque.bloomtrade.auth.repository.UserRepository;
import co.edu.unbosque.bloomtrade.notification.Notifier;
import co.edu.unbosque.bloomtrade.notification.dto.OrderExecutedEmailCommand;
import co.edu.unbosque.bloomtrade.notification.dto.OrderFailedEmailCommand;
import co.edu.unbosque.bloomtrade.notification.dto.OrderRejectedEmailCommand;
import co.edu.unbosque.bloomtrade.trading.domain.OrderSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * IT del {@link OrderEventListener}. Verifica:
 * <ul>
 *   <li>{@code @TransactionalEventListener(AFTER_COMMIT)} dispara el listener TRAS commit
 *       exitoso de la tx que publica el evento.</li>
 *   <li>Rollback de la tx publicadora descarta el evento — listener NO se ejecuta.</li>
 *   <li>{@code INSUFFICIENT_FUNDS} en rejected → emite audit pero NO email (SPEC §9.2).</li>
 *   <li>Otros rechazos sí envían email.</li>
 *   <li>Failed envía audit + email.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderEventListenerTest {

    @MockBean private Notifier notifier;
    @MockBean private Auditor auditor;

    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID userId;
    private UUID orderId;

    @BeforeEach
    void setup() {
        jdbcTemplate.execute("TRUNCATE app.orders, app.positions, app.user_balances, app.users CASCADE");
        User user =
                User.register(
                        "listener-" + UUID.randomUUID() + "@test.local",
                        "$2a$12$dummyHashForTestingOnlyNotARealBcryptHashAtAllxx",
                        "Listener Test",
                        DocumentType.CC,
                        "5000" + System.nanoTime() % 1_000_000,
                        "+573009998877",
                        Instant.now());
        user = userRepository.save(user);
        userId = user.getId();
        orderId = UUID.randomUUID();
    }

    @Test
    void onOrderExecuted_afterCommit_emitsTwoAuditEventsAndOneEmail() {
        OrderExecutedEvent event = sampleExecutedEvent();

        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));

        // 2 audit events: ORDER_CREATED + ORDER_EXECUTED.
        verify(auditor)
                .record(argThat(e -> e.eventType() == AuditEventType.ORDER_CREATED));
        verify(auditor)
                .record(argThat(e -> e.eventType() == AuditEventType.ORDER_EXECUTED));
        verify(notifier, times(1)).sendOrderExecutedEmailBuy(any(OrderExecutedEmailCommand.class));
        verify(notifier, never()).sendOrderExecutedEmailSell(any());
    }

    @Test
    void onOrderExecuted_afterRollback_doesNotEmitAudit_orEmail() {
        OrderExecutedEvent event = sampleExecutedEvent();

        transactionTemplate.executeWithoutResult(
                status -> {
                    eventPublisher.publishEvent(event);
                    status.setRollbackOnly();
                });

        verify(auditor, never()).record(any(AuditEvent.class));
        verify(notifier, never()).sendOrderExecutedEmailBuy(any());
        verify(notifier, never()).sendOrderExecutedEmailSell(any());
    }

    @Test
    void onOrderRejected_insufficientFunds_emitsAuditButSkipsEmail() {
        OrderRejectedEvent event =
                new OrderRejectedEvent(
                        orderId, userId, "AAPL", OrderSide.BUY, 10, "INSUFFICIENT_FUNDS", null);

        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));

        verify(auditor)
                .record(argThat(e -> e.eventType() == AuditEventType.ORDER_REJECTED));
        verify(notifier, never()).sendOrderRejectedEmailBuy(any());
        verify(notifier, never()).sendOrderRejectedEmailSell(any());
    }

    @Test
    void onOrderRejected_alpacaRejected_emitsAuditAndEmail() {
        OrderRejectedEvent event =
                new OrderRejectedEvent(
                        orderId,
                        userId,
                        "AAPL",
                        OrderSide.BUY,
                        10,
                        "ALPACA_ORDER_REJECTED",
                        "qty exceeds buying power");

        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));

        verify(auditor)
                .record(argThat(e -> e.eventType() == AuditEventType.ORDER_REJECTED));
        verify(notifier, times(1)).sendOrderRejectedEmailBuy(any(OrderRejectedEmailCommand.class));
        verify(notifier, never()).sendOrderRejectedEmailSell(any());
    }

    @Test
    void onOrderFailed_emitsAuditAndEmail() {
        OrderFailedEvent event =
                new OrderFailedEvent(
                        orderId,
                        userId,
                        "AAPL",
                        OrderSide.BUY,
                        10,
                        "ALPACA_API_ERROR",
                        "Alpaca down tras retries");

        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));

        verify(auditor)
                .record(argThat(e -> e.eventType() == AuditEventType.ORDER_FAILED));
        verify(notifier, times(1)).sendOrderFailedEmailBuy(any(OrderFailedEmailCommand.class));
        verify(notifier, never()).sendOrderFailedEmailSell(any());
    }

    // ─── HU-F10 Lote C — dispatch SELL (T3.10) ──────────────────────────────

    @Test
    void onOrderExecuted_sideSell_invokesNotifyExecutedSell_notBuy() {
        OrderExecutedEvent event =
                new OrderExecutedEvent(
                        orderId,
                        userId,
                        "AAPL",
                        OrderSide.SELL,
                        5,
                        new BigDecimal("189.9500"),
                        new BigDecimal("930.75"),
                        new BigDecimal("19.00"),
                        new BigDecimal("9047.65"),
                        "alp-sell-1",
                        5 /* positionResultingQty */,
                        Boolean.FALSE);

        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));

        verify(notifier, times(1)).sendOrderExecutedEmailSell(any(OrderExecutedEmailCommand.class));
        verify(notifier, never()).sendOrderExecutedEmailBuy(any());
        // Audit ORDER_EXECUTED emitido con detalles SELL.
        verify(auditor)
                .record(argThat(e -> e.eventType() == AuditEventType.ORDER_EXECUTED));
    }

    @Test
    void onOrderRejected_sideSell_invokesNotifyRejectedSell_notBuy() {
        OrderRejectedEvent event =
                new OrderRejectedEvent(
                        orderId,
                        userId,
                        "AAPL",
                        OrderSide.SELL,
                        5,
                        "ALPACA_ORDER_REJECTED",
                        "asset not tradeable");

        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));

        verify(notifier, times(1)).sendOrderRejectedEmailSell(any(OrderRejectedEmailCommand.class));
        verify(notifier, never()).sendOrderRejectedEmailBuy(any());
    }

    @Test
    void onOrderRejected_sideSellShortSelling_emitsAuditButSkipsEmail() {
        OrderRejectedEvent event =
                new OrderRejectedEvent(
                        orderId, userId, "AAPL", OrderSide.SELL, 5, "SHORT_SELLING_NOT_ALLOWED", null);

        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));

        verify(auditor)
                .record(argThat(e -> e.eventType() == AuditEventType.ORDER_REJECTED));
        verify(notifier, never()).sendOrderRejectedEmailSell(any());
        verify(notifier, never()).sendOrderRejectedEmailBuy(any());
    }

    @Test
    void onOrderRejected_sideSellInsufficientShares_emitsAuditButSkipsEmail() {
        OrderRejectedEvent event =
                new OrderRejectedEvent(
                        orderId, userId, "AAPL", OrderSide.SELL, 5, "INSUFFICIENT_SHARES", null);

        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));

        verify(auditor)
                .record(argThat(e -> e.eventType() == AuditEventType.ORDER_REJECTED));
        verify(notifier, never()).sendOrderRejectedEmailSell(any());
    }

    @Test
    void onOrderFailed_sideSell_invokesNotifyFailedSell_notBuy() {
        OrderFailedEvent event =
                new OrderFailedEvent(
                        orderId,
                        userId,
                        "AAPL",
                        OrderSide.SELL,
                        5,
                        "ALPACA_API_ERROR",
                        "Alpaca down tras retries");

        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));

        verify(notifier, times(1)).sendOrderFailedEmailSell(any(OrderFailedEmailCommand.class));
        verify(notifier, never()).sendOrderFailedEmailBuy(any());
    }

    private OrderExecutedEvent sampleExecutedEvent() {
        return new OrderExecutedEvent(
                orderId,
                userId,
                "AAPL",
                OrderSide.BUY,
                10,
                new BigDecimal("184.6200"),
                new BigDecimal("1883.10"),
                new BigDecimal("36.90"),
                new BigDecimal("8116.90"),
                "alpaca-xyz",
                10 /* positionResultingQty BUY */,
                Boolean.FALSE);
    }
}
