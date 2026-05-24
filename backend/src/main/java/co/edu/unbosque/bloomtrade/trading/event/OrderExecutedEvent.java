package co.edu.unbosque.bloomtrade.trading.event;

import co.edu.unbosque.bloomtrade.trading.domain.OrderSide;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Evento de dominio: orden ejecutada exitosamente (HU-F09 D15, extendido HU-F10 D11). Publicado
 * por {@code TradingService} al final de la transacción; recogido por {@code OrderEventListener}
 * con {@code @TransactionalEventListener(phase=AFTER_COMMIT)} para disparar email y audit log
 * SIN bloquear la transacción.
 *
 * <p><b>HU-F10 — campos nuevos</b>:
 * <ul>
 *   <li>{@code side}: discrimina BUY/SELL — el listener despacha al método {@code Notifier}
 *       correcto (D7 SPEC §8.3) y el audit detail correspondiente.</li>
 *   <li>{@code positionResultingQty}: cantidad de la posición tras la mutación. Para BUY es
 *       qty post-upsert; para SELL es qty residual o 0 si la fila fue borrada por liquidación.</li>
 *   <li>{@code positionDeleted}: solo {@code true} para SELL que liquidó la posición completa
 *       (qty resultante = 0 → fila borrada por D1). Para BUY siempre {@code false}.</li>
 * </ul>
 */
public record OrderExecutedEvent(
        UUID orderId,
        UUID userId,
        String ticker,
        OrderSide side,
        int quantity,
        BigDecimal executionUnitPrice,
        BigDecimal executionTotal,
        BigDecimal commission,
        BigDecimal newBalance,
        String alpacaOrderId,
        Integer positionResultingQty,
        Boolean positionDeleted) {}
