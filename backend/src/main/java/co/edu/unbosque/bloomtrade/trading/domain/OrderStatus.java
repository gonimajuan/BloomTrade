package co.edu.unbosque.bloomtrade.trading.domain;

/**
 * Estado de una orden en su FSM. HU-F09 D16: enum estrechado a 4 valores frente a los
 * 10 listados en ARCH §9 (Pendiente, Enviada, En Ejecución, Ejecutada, Cancelada,
 * Rechazada, Expirada, En Revisión, Fallida, Detenida). MVP cubre estrictamente lo
 * necesario para el flujo Market BUY/SELL síncrono.
 *
 * <p>Transiciones permitidas:
 * <ul>
 *   <li>{@code PENDING} → {@code EXECUTED} (Alpaca devolvió status=filled)</li>
 *   <li>{@code PENDING} → {@code REJECTED} (Alpaca rechazó explícitamente)</li>
 *   <li>{@code PENDING} → {@code FAILED}   (Alpaca caído tras 3 retries, o error técnico)</li>
 * </ul>
 *
 * <p>{@code CANCELLED} se introducirá en HU-F15 (stretch goal Sprint 2). Cualquier
 * extensión futura agrega el valor + handler en el mismo PR + actualiza
 * {@code chk_order_status} en una nueva migración.
 */
public enum OrderStatus {
    PENDING,
    EXECUTED,
    REJECTED,
    FAILED
}
