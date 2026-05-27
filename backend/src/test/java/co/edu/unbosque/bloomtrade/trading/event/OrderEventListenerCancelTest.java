package co.edu.unbosque.bloomtrade.trading.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.unbosque.bloomtrade.audit.AuditEvent;
import co.edu.unbosque.bloomtrade.audit.AuditEventType;
import co.edu.unbosque.bloomtrade.audit.Auditor;
import co.edu.unbosque.bloomtrade.auth.domain.DocumentType;
import co.edu.unbosque.bloomtrade.auth.domain.User;
import co.edu.unbosque.bloomtrade.auth.repository.UserRepository;
import co.edu.unbosque.bloomtrade.notification.Notifier;
import co.edu.unbosque.bloomtrade.notification.dto.OrderCanceledEmailCommand;
import co.edu.unbosque.bloomtrade.trading.domain.OrderSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests de los 3 listeners HU-F15 agregados a {@link OrderEventListener} (T2.21):
 * {@code onOrderCanceled}, {@code onOrderCancelPending}, {@code onOrderExpired}.
 *
 * <p>A diferencia del IT existente {@code OrderEventListenerTest} ({@code @SpringBootTest}), este
 * test es unit puro con Mockito — invoca los métodos del listener directamente sin ApplicationContext.
 * Más rápido y suficiente para verificar el dispatch por side + flag isExpired.
 */
@ExtendWith(MockitoExtension.class)
class OrderEventListenerCancelTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORDER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock private Auditor auditor;
    @Mock private Notifier notifier;
    @Mock private UserRepository userRepository;

    private OrderEventListener listener;

    @BeforeEach
    void setup() {
        listener = new OrderEventListener(auditor, notifier, userRepository);
        User user =
                User.register(
                        "test@example.com",
                        "$2a$12$hashed",
                        "Test User",
                        DocumentType.CC,
                        "12345678",
                        "+573001234567",
                        Instant.now());
        // lenient: onOrderCancelPending no usa userRepository (es info-only) y Mockito strict
        // flaggearía esto como UnnecessaryStubbing.
        lenient().when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
    }

    @Test
    void onOrderCanceled_buy_emitsAuditAndDispatchesEmailBuy() {
        OrderCanceledEvent event =
                new OrderCanceledEvent(
                        ORDER_ID,
                        USER_ID,
                        "AAPL",
                        OrderSide.BUY,
                        5,
                        "alp-xyz-123",
                        new BigDecimal("1020.00"),
                        new BigDecimal("1020.00"),
                        null,
                        Instant.now(),
                        OrderCanceledEvent.CancelSource.USER_REQUEST);

        listener.onOrderCanceled(event);

        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditor).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().eventType()).isEqualTo(AuditEventType.ORDER_CANCELED);

        ArgumentCaptor<OrderCanceledEmailCommand> cmdCaptor =
                ArgumentCaptor.forClass(OrderCanceledEmailCommand.class);
        verify(notifier).sendOrderCanceledEmailBuy(cmdCaptor.capture());
        verify(notifier, never()).sendOrderCanceledEmailSell(any());
        OrderCanceledEmailCommand cmd = cmdCaptor.getValue();
        assertThat(cmd.isExpired()).isFalse();
        assertThat(cmd.refundedAmount()).isEqualTo(new BigDecimal("1020.00"));
        assertThat(cmd.restoredQty()).isNull();
    }

    @Test
    void onOrderCanceled_sell_emitsAuditAndDispatchesEmailSell() {
        OrderCanceledEvent event =
                new OrderCanceledEvent(
                        ORDER_ID,
                        USER_ID,
                        "AAPL",
                        OrderSide.SELL,
                        5,
                        "alp-xyz-123",
                        new BigDecimal("980.00"),
                        null,
                        5,
                        Instant.now(),
                        OrderCanceledEvent.CancelSource.USER_REQUEST);

        listener.onOrderCanceled(event);

        verify(notifier).sendOrderCanceledEmailSell(any());
        verify(notifier, never()).sendOrderCanceledEmailBuy(any());
    }

    @Test
    void onOrderCancelPending_doesNotCallNotifier() {
        OrderCancelPendingEvent event =
                new OrderCancelPendingEvent(
                        ORDER_ID,
                        USER_ID,
                        "AAPL",
                        OrderSide.BUY,
                        5,
                        "alp-xyz-123",
                        Instant.now());

        listener.onOrderCancelPending(event);

        // Solo log — sin audit, sin email.
        verify(notifier, never()).sendOrderCanceledEmailBuy(any());
        verify(notifier, never()).sendOrderCanceledEmailSell(any());
    }

    @Test
    void onOrderExpired_buy_emitsAuditAndDispatchesEmailWithIsExpiredFlag() {
        OrderExpiredEvent event =
                new OrderExpiredEvent(
                        ORDER_ID,
                        USER_ID,
                        "AAPL",
                        OrderSide.BUY,
                        5,
                        "alp-xyz-123",
                        new BigDecimal("1020.00"),
                        new BigDecimal("1020.00"),
                        null,
                        Instant.now());

        listener.onOrderExpired(event);

        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditor).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().eventType()).isEqualTo(AuditEventType.ORDER_EXPIRED);

        ArgumentCaptor<OrderCanceledEmailCommand> cmdCaptor =
                ArgumentCaptor.forClass(OrderCanceledEmailCommand.class);
        verify(notifier).sendOrderCanceledEmailBuy(cmdCaptor.capture());
        // El isExpired=true cambia el subject y el copy via Thymeleaf (D15 D-EMAIL-EXPIRED-REUSE).
        assertThat(cmdCaptor.getValue().isExpired()).isTrue();
    }
}
