package co.edu.unbosque.bloomtrade.trading.mapper;

import co.edu.unbosque.bloomtrade.trading.domain.Order;
import co.edu.unbosque.bloomtrade.trading.dto.OrderResponse;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * Mapeo manual {@link Order} entity → {@link OrderResponse} DTO (HU-F09 Lote E).
 *
 * <p>Por qué manual y no MapStruct (a diferencia de {@code SubscriptionMapper}):
 * los precios y montos requieren conversión {@link BigDecimal} → {@link String} con
 * {@code .toPlainString()} para preservar precisión en JSON (NUMERIC(19,4) en BD; los
 * {@code number} de JS son IEEE 754 double y pierden precisión en valores grandes). MapStruct
 * lo haría también pero el qualifier custom agrega más boilerplate que esta clase entera.
 */
@Component
public class OrderMapper {

    public OrderResponse toResponse(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getClientOrderId(),
                order.getTicker(),
                order.getSide(),
                order.getType(),
                order.getQuantity(),
                toPlainString(order.getQuotedUnitPrice()),
                toPlainString(order.getExecutionUnitPrice()),
                toPlainString(order.getQuotedCommission()),
                toPlainString(order.getQuotedTotal()),
                toPlainString(order.getExecutionTotal()),
                order.getStatus(),
                order.getAlpacaOrderId(),
                order.getErrorCode(),
                order.getErrorMessage(),
                order.getSubmittedAt(),
                order.getExecutedAt());
    }

    private static String toPlainString(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
