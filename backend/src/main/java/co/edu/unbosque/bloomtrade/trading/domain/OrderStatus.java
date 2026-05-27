package co.edu.unbosque.bloomtrade.trading.domain;

/**
 * Estado de una orden en su FSM. HU-F09 D16: enum estrechado frente a los 10 listados en
 * ARCH §9 (Pendiente, Enviada, En Ejecución, Ejecutada, Cancelada, Rechazada, Expirada, En
 * Revisión, Fallida, Detenida). HU-F15 D3: extendido a 6 valores con {@code CANCELED} y
 * {@code EXPIRED}; los restantes ({@code IN_REVIEW}, {@code STOPPED}) siguen fuera de scope
 * por sobre-ingeniería para Alpaca paper.
 *
 * <p>Transiciones permitidas:
 * <ul>
 *   <li>{@code PENDING} → {@code EXECUTED} (Alpaca devolvió status=filled)</li>
 *   <li>{@code PENDING} → {@code REJECTED} (Alpaca rechazó explícitamente)</li>
 *   <li>{@code PENDING} → {@code FAILED}   (Alpaca caído tras 3 retries, o error técnico)</li>
 *   <li>{@code PENDING} → {@code CANCELED} (HU-F15: cancel por usuario o broker; reverse aplicado)</li>
 *   <li>{@code PENDING} → {@code EXPIRED}  (HU-F15: TIF day expirado sin fill; reverse aplicado)</li>
 * </ul>
 *
 * <p>{@code CHECK (status IN ('PENDING', 'EXECUTED', 'REJECTED', 'FAILED', 'CANCELED', 'EXPIRED'))}
 * en BD (migración V6) refuerza la invariante a nivel persistencia.
 */
public enum OrderStatus {
    PENDING,
    EXECUTED,
    REJECTED,
    FAILED,
    CANCELED,
    EXPIRED
}
