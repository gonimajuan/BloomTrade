package co.edu.unbosque.bloomtrade.trading.exception;

import java.util.UUID;

/**
 * Cancel solicitado sobre una orden inexistente o que pertenece a otro usuario (HU-F15 §5.3.1).
 *
 * <p><b>Defensa anti-enumeración</b>: el {@code GlobalExceptionHandler} mapea esta excepción a
 * HTTP 404 {@code ORDER_NOT_FOUND} con body genérico — NO se revela si "no existe" o "es ajena".
 * Un atacante no debe poder probar UUIDs para identificar órdenes de otros usuarios.
 *
 * <p>El {@code orderId} se preserva en el campo (no expuesto al cliente) para audit/debug interno.
 */
public class OrderNotFoundException extends RuntimeException {

    private final UUID orderId;

    public OrderNotFoundException(UUID orderId) {
        super("No se encontró la orden: " + orderId);
        this.orderId = orderId;
    }

    public UUID getOrderId() {
        return orderId;
    }
}
