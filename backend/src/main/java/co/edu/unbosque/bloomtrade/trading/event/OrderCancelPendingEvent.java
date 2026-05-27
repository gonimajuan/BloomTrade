package co.edu.unbosque.bloomtrade.trading.event;

import co.edu.unbosque.bloomtrade.trading.domain.OrderSide;
import java.time.Instant;
import java.util.UUID;

/**
 * Evento de dominio info-only: cancel solicitado pero polling Alpaca dio timeout (HU-F15
 * §5.2.1). El order sigue {@code status=PENDING} con {@code cancel_requested_at} poblado;
 * {@code OrderReconciliationService} v2 materializará la transición a CANCELED en el próximo
 * GET del usuario.
 *
 * <p>El listener {@code OrderEventListener.handleOrderCancelPending} emite audit
 * {@code ORDER_CANCEL_REQUESTED} con {@code outcome=PENDING_CANCEL} pero NO dispara email — el
 * email se enviará cuando la transición se materialice y se publique {@link OrderCanceledEvent}.
 */
public record OrderCancelPendingEvent(
        UUID orderId,
        UUID userId,
        String ticker,
        OrderSide side,
        int quantity,
        String alpacaOrderId,
        Instant cancelRequestedAt) {}
