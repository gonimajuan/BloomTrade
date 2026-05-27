package co.edu.unbosque.bloomtrade.trading.event;

import co.edu.unbosque.bloomtrade.trading.domain.OrderSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Evento de dominio: orden expirada (HU-F15). Publicado por {@code OrderReconciliationService} v2
 * cuando detecta que Alpaca reportó {@code status=expired} para una orden PENDING+alpacaOrderId
 * (TIF day expirado sin fill). El reverse de balance/position se aplica igual que en
 * {@link OrderCanceledEvent}.
 *
 * <p>El listener reusa los templates {@code order-canceled-{buy,sell}.html} con flag
 * {@code isExpired=true} en el context Thymeleaf (D15 D-EMAIL-EXPIRED-REUSE).
 */
public record OrderExpiredEvent(
        UUID orderId,
        UUID userId,
        String ticker,
        OrderSide side,
        int quantity,
        String alpacaOrderId,
        BigDecimal quotedTotal,
        BigDecimal refundedAmount,
        Integer restoredQty,
        Instant expiredAt) {}
