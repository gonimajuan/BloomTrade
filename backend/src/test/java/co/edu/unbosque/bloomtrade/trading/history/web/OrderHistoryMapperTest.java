package co.edu.unbosque.bloomtrade.trading.history.web;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.unbosque.bloomtrade.trading.domain.Order;
import co.edu.unbosque.bloomtrade.trading.domain.OrderSide;
import co.edu.unbosque.bloomtrade.trading.domain.OrderStatus;
import co.edu.unbosque.bloomtrade.trading.domain.OrderType;
import co.edu.unbosque.bloomtrade.trading.history.dto.OrderHistoryDto;
import co.edu.unbosque.bloomtrade.trading.history.dto.OrderHistoryResponse;
import co.edu.unbosque.bloomtrade.trading.history.dto.PaginationDto;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * Tests unit del {@link OrderHistoryMapper} (HU-F17 Lote C). Cubre stringification scale=2,
 * null-safe para campos opcionales y conversión {@code Page<Order> → OrderHistoryResponse}.
 */
class OrderHistoryMapperTest {

    private OrderHistoryMapper mapper;

    @BeforeEach
    void setup() {
        mapper = new OrderHistoryMapper();
    }

    private static Order pending() {
        return Order.newPending(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "AAPL",
                OrderSide.BUY,
                OrderType.MARKET,
                10,
                new BigDecimal("190.0000"),
                new BigDecimal("38.0000"),
                new BigDecimal("1938.0000"));
    }

    @Test
    void toOrderHistoryDto_executedOrder_allFieldsPopulated() {
        Order order = pending();
        order.markAsExecuted("alpaca-abc-123", new BigDecimal("193.20"));

        OrderHistoryDto dto = mapper.toOrderHistoryDto(order);

        assertThat(dto.status()).isEqualTo(OrderStatus.EXECUTED);
        assertThat(dto.ticker()).isEqualTo("AAPL");
        assertThat(dto.side()).isEqualTo(OrderSide.BUY);
        assertThat(dto.quantity()).isEqualTo(10);
        assertThat(dto.alpacaOrderId()).isEqualTo("alpaca-abc-123");
        // execution_total BUY = 193.20×10 + 38.00 = 1970.00
        assertThat(dto.executionTotal()).isEqualTo("1970.00");
        assertThat(dto.averageFillPrice()).isEqualTo("193.20");
        assertThat(dto.commission()).isEqualTo("38.00");
        assertThat(dto.executedAt()).isNotNull();
        assertThat(dto.failureReason()).isNull();
    }

    @Test
    void toOrderHistoryDto_pendingOrder_executionFieldsNull() {
        Order order = pending();

        OrderHistoryDto dto = mapper.toOrderHistoryDto(order);

        assertThat(dto.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(dto.executedAt()).isNull();
        assertThat(dto.executionTotal()).isNull();
        assertThat(dto.averageFillPrice()).isNull();
        assertThat(dto.alpacaOrderId()).isNull();
        // commission siempre presente (es del quote, no de la ejecución).
        assertThat(dto.commission()).isEqualTo("38.00");
        assertThat(dto.failureReason()).isNull();
    }

    @Test
    void toOrderHistoryDto_failedOrder_failureReasonPopulated() {
        Order order = pending();
        order.markAsFailed("ALPACA_API_ERROR", "Alpaca devolvió 503 tras 3 retries");

        OrderHistoryDto dto = mapper.toOrderHistoryDto(order);

        assertThat(dto.status()).isEqualTo(OrderStatus.FAILED);
        assertThat(dto.failureReason()).isEqualTo("ALPACA_API_ERROR");
        assertThat(dto.executionTotal()).isNull();
    }

    @Test
    void toOrderHistoryDto_rejectedOrder_failureReasonPopulated() {
        Order order = pending();
        order.markAsRejected("INSUFFICIENT_FUNDS", "Saldo insuficiente");

        OrderHistoryDto dto = mapper.toOrderHistoryDto(order);

        assertThat(dto.status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(dto.failureReason()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void toPaginationDto_extractsFromPage() {
        Page<Order> page = new PageImpl<>(List.of(pending()), PageRequest.of(2, 5), 47);

        PaginationDto dto = mapper.toPaginationDto(page);

        assertThat(dto.page()).isEqualTo(2);
        assertThat(dto.size()).isEqualTo(5);
        assertThat(dto.totalElements()).isEqualTo(47);
        assertThat(dto.totalPages()).isEqualTo(10); // ceil(47/5)
    }

    @Test
    void toOrderHistoryResponse_combinesContentAndPagination() {
        Order o1 = pending();
        Order o2 = pending();
        Page<Order> page = new PageImpl<>(List.of(o1, o2), PageRequest.of(0, 10), 2);

        OrderHistoryResponse response = mapper.toOrderHistoryResponse(page);

        assertThat(response.content()).hasSize(2);
        assertThat(response.pagination().totalElements()).isEqualTo(2);
        assertThat(response.pagination().totalPages()).isEqualTo(1);
    }
}
