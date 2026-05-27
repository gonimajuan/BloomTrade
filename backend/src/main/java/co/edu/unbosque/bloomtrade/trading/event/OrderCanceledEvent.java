package co.edu.unbosque.bloomtrade.trading.event;

import co.edu.unbosque.bloomtrade.trading.domain.OrderSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Evento de dominio: orden cancelada (HU-F15). Publicado por {@code TradingService.cancelOrder}
 * (polling-OK) o por {@code OrderReconciliationService} v2 (reconcile lazy materializa cancel
 * pendiente o detecta canceled outbound del broker). Recogido por
 * {@code OrderEventListener.handleOrderCanceled} con {@code @TransactionalEventListener(AFTER_COMMIT)}
 * para disparar email y audit log SIN bloquear la transacción.
 *
 * <p>{@code refundedAmount} y {@code restoredQty} son side-mutually-exclusive:
 * <ul>
 *   <li>BUY canceled → {@code refundedAmount = quotedTotal}, {@code restoredQty = null}.</li>
 *   <li>SELL canceled → {@code refundedAmount = null}, {@code restoredQty = quantity}.</li>
 * </ul>
 *
 * <p>{@code source} discrimina el origen de la cancelación para auditoría y debugging:
 * <ul>
 *   <li>{@link CancelSource#USER_REQUEST}: usuario invocó {@code POST /orders/{id}/cancel} (polling-OK).</li>
 *   <li>{@link CancelSource#BROKER_CANCEL}: Alpaca canceló outbound (timeout TIF day, etc.) y
 *       reconcile lazy lo materializó.</li>
 *   <li>{@link CancelSource#DRIFT_RECONCILE}: cancel-request reveló drift (Alpaca 404/422), reconcile
 *       inline materializó el estado real.</li>
 * </ul>
 */
public record OrderCanceledEvent(
        UUID orderId,
        UUID userId,
        String ticker,
        OrderSide side,
        int quantity,
        String alpacaOrderId,
        BigDecimal quotedTotal,
        BigDecimal refundedAmount,
        Integer restoredQty,
        Instant canceledAt,
        CancelSource source) {

    public enum CancelSource {
        USER_REQUEST,
        BROKER_CANCEL,
        DRIFT_RECONCILE
    }
}
