package co.edu.unbosque.bloomtrade.trading.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Evento de dominio: la orden fue aceptada por Alpaca pero NO se llenó dentro de la ventana de
 * polling síncrono (típicamente por mercado cerrado fuera de horario). HU-F09 D29 emergente del
 * Lote H.5: separamos este caso del {@link OrderFailedEvent} porque la orden no falló — está en
 * cola esperando que abra el mercado.
 *
 * <p>Efectos colaterales post-commit (en {@code OrderEventListener.onOrderQueued}):
 * <ul>
 *   <li>Audit log {@code ORDER_CREATED} + {@code ORDER_QUEUED}.</li>
 *   <li>Email "tu orden quedó en cola" — separado del template de FAILED para no confundir.</li>
 * </ul>
 *
 * <p>{@code newBalance} es el saldo tras el débito reservado del {@code quotedTotal} (D29: se
 * debita ya para evitar double-spend con otra orden concurrente; deuda separada de reconciliación
 * cuando Alpaca finalmente fillea con precio distinto al cotizado).
 */
public record OrderQueuedEvent(
        UUID orderId,
        UUID userId,
        String ticker,
        int quantity,
        BigDecimal quotedUnitPrice,
        BigDecimal commission,
        BigDecimal quotedTotal,
        BigDecimal newBalance,
        String alpacaOrderId) {}
