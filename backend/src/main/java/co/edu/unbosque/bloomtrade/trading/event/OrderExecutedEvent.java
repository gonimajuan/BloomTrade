package co.edu.unbosque.bloomtrade.trading.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Evento de dominio: orden ejecutada exitosamente (HU-F09 D15). Publicado por
 * {@code TradingService} al final de la transacción; recogido por {@code OrderEventListener}
 * con {@code @TransactionalEventListener(phase=AFTER_COMMIT)} (Lote F) para disparar email
 * y audit log SIN bloquear la transacción.
 */
public record OrderExecutedEvent(
        UUID orderId,
        UUID userId,
        String ticker,
        int quantity,
        BigDecimal executionUnitPrice,
        BigDecimal executionTotal,
        BigDecimal commission,
        BigDecimal newBalance,
        String alpacaOrderId) {}
