package co.edu.unbosque.bloomtrade.trading.exception;

import co.edu.unbosque.bloomtrade.trading.domain.OrderStatus;
import java.util.UUID;

/**
 * Cancel solicitado sobre orden en estado terminal no cancelable (HU-F15 §5.3.2). Estados
 * cubiertos: {@link OrderStatus#EXECUTED}, {@link OrderStatus#REJECTED}, {@link OrderStatus#FAILED},
 * {@link OrderStatus#EXPIRED}. {@link OrderStatus#CANCELED} NO usa esta excepción — se trata vía
 * short-circuit idempotency en {@code TradingService.cancelOrder} (D7).
 *
 * <p>El {@code GlobalExceptionHandler} mapea a HTTP 409 {@code ORDER_NOT_CANCELABLE} con
 * {@code details.currentStatus} para que el cliente muestre mensaje específico por estado.
 */
public class OrderNotCancelableException extends RuntimeException {

    private final UUID orderId;
    private final OrderStatus currentStatus;

    public OrderNotCancelableException(UUID orderId, OrderStatus currentStatus) {
        super("Orden " + orderId + " está en estado " + currentStatus + " y no puede cancelarse");
        this.orderId = orderId;
        this.currentStatus = currentStatus;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public OrderStatus getCurrentStatus() {
        return currentStatus;
    }
}
