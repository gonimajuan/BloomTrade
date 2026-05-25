package co.edu.unbosque.bloomtrade.trading.history.web;

import co.edu.unbosque.bloomtrade.trading.domain.Order;
import co.edu.unbosque.bloomtrade.trading.history.dto.OrderHistoryDto;
import co.edu.unbosque.bloomtrade.trading.history.dto.OrderHistoryResponse;
import co.edu.unbosque.bloomtrade.trading.history.dto.PaginationDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/**
 * Mapper manual del historial de órdenes (HU-F17 plan D14).
 *
 * <p>BigDecimal stringificación scale=2 HALF_UP (plan D13). Null-safe para campos opcionales
 * según el estado de la orden (EXECUTED/PENDING/REJECTED/FAILED).
 */
@Component
public class OrderHistoryMapper {

    public OrderHistoryDto toOrderHistoryDto(Order order) {
        return new OrderHistoryDto(
                order.getId(),
                order.getClientOrderId(),
                order.getTicker(),
                order.getSide(),
                order.getQuantity(),
                order.getStatus(),
                order.getSubmittedAt(),
                order.getExecutedAt(),
                stringifyScale2(order.getExecutionTotal()),
                stringifyScale2(order.getExecutionUnitPrice()),
                stringifyScale2(order.getQuotedCommission()),
                order.getAlpacaOrderId(),
                order.getErrorCode());
    }

    public PaginationDto toPaginationDto(Page<?> page) {
        return new PaginationDto(
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    public OrderHistoryResponse toOrderHistoryResponse(Page<Order> page) {
        List<OrderHistoryDto> content =
                page.getContent().stream().map(this::toOrderHistoryDto).toList();
        return new OrderHistoryResponse(content, toPaginationDto(page));
    }

    private String stringifyScale2(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
