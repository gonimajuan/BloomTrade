package co.edu.unbosque.bloomtrade.trading.event;

import co.edu.unbosque.bloomtrade.trading.domain.OrderSide;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Evento de dominio: la orden fue aceptada por Alpaca pero NO se llenó dentro de la ventana de
 * polling síncrono (típicamente por mercado cerrado fuera de horario). HU-F09 D29 emergente del
 * Lote H.5; HU-F10 D9 extendido a SELL.
 *
 * <p>Efectos colaterales post-commit (en {@code OrderEventListener.onOrderQueued}):
 * <ul>
 *   <li>Audit log {@code ORDER_CREATED} + {@code ORDER_QUEUED} (con {@code side} en details).</li>
 *   <li>Email "tu orden quedó en cola" — wording side-aware (Lote C).</li>
 * </ul>
 *
 * <p><b>BUY queued</b> (D29 F09): se debita {@code quotedTotal} del balance para evitar
 * double-spend con otra orden concurrente. {@code newBalance} refleja el balance post-débito.
 * {@code positionResultingQty} es {@code null} (la posición no se toca hasta el fill).
 *
 * <p><b>SELL queued</b> (D9 F10): se DECREMENTA la posición optimistamente para evitar que el
 * usuario venda dos veces la misma tenencia. {@code newBalance} refleja el balance ACTUAL (sin
 * cambios — el crédito ocurrirá cuando Alpaca fillee, vía reconciliation). {@code positionResultingQty}
 * es la cantidad post-decrement (0 si la fila fue borrada por liquidación total).
 *
 * <p>Trade-off documentado D9: si Alpaca cancela una orden encolada (caso edge), el usuario
 * perdió posición sin recibir crédito. Deuda registrada en AGENTS.md para reconciliation post-MVP.
 */
public record OrderQueuedEvent(
        UUID orderId,
        UUID userId,
        String ticker,
        OrderSide side,
        int quantity,
        BigDecimal quotedUnitPrice,
        BigDecimal commission,
        BigDecimal quotedTotal,
        BigDecimal newBalance,
        String alpacaOrderId,
        Integer positionResultingQty) {}
