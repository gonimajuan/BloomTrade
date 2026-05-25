package co.edu.unbosque.bloomtrade.portfolio.web;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.unbosque.bloomtrade.portfolio.domain.Position;
import co.edu.unbosque.bloomtrade.portfolio.domain.UserBalance;
import co.edu.unbosque.bloomtrade.portfolio.dto.BalanceResponse;
import co.edu.unbosque.bloomtrade.portfolio.dto.PendingOrderDto;
import co.edu.unbosque.bloomtrade.portfolio.dto.PortfolioPositionsResponse;
import co.edu.unbosque.bloomtrade.portfolio.dto.PositionDto;
import co.edu.unbosque.bloomtrade.trading.domain.Order;
import co.edu.unbosque.bloomtrade.trading.domain.OrderSide;
import co.edu.unbosque.bloomtrade.trading.domain.OrderType;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Tests unit de {@link PortfolioMapper} (HU-F16+F21 Lote C — T3.3–T3.7). Sin Spring context;
 * mapper es POJO instanciable directo. Entidades JPA con setters protected — se construyen
 * vía sus factory methods o, para los timestamps gestionados por Hibernate (createdAt,
 * updatedAt), por reflection.
 */
class PortfolioMapperTest {

    private final PortfolioMapper mapper = new PortfolioMapper();

    // ─── toPositionDto ──────────────────────────────────────────────────────────

    @Test
    void toPositionDto_withCurrentPrice_calculatesAllFields() {
        Position position = newPosition("AAPL", 10, new BigDecimal("189.4500"));

        PositionDto dto = mapper.toPositionDto(position, new BigDecimal("193.2000"));

        assertThat(dto.ticker()).isEqualTo("AAPL");
        assertThat(dto.quantity()).isEqualTo(10);
        assertThat(dto.avgCost()).isEqualTo("189.45");
        assertThat(dto.costBasis()).isEqualTo("1894.50");
        assertThat(dto.currency()).isEqualTo("USD");
        assertThat(dto.currentPrice()).isEqualTo("193.20");
        assertThat(dto.marketValue()).isEqualTo("1932.00");
        assertThat(dto.unrealizedPnL()).isEqualTo("37.50");
        assertThat(dto.unrealizedPnLPct()).isEqualTo("1.98");
    }

    @Test
    void toPositionDto_withNegativePnL_signPreserved() {
        Position position = newPosition("MSFT", 5, new BigDecimal("412.0000"));

        PositionDto dto = mapper.toPositionDto(position, new BigDecimal("408.5000"));

        assertThat(dto.marketValue()).isEqualTo("2042.50");
        assertThat(dto.unrealizedPnL()).isEqualTo("-17.50");
        assertThat(dto.unrealizedPnLPct()).isEqualTo("-0.85");
    }

    @Test
    void toPositionDto_withNullPrice_nullsAllMarketFields() {
        Position position = newPosition("AAPL", 10, new BigDecimal("189.4500"));

        PositionDto dto = mapper.toPositionDto(position, null);

        assertThat(dto.avgCost()).isEqualTo("189.45");
        assertThat(dto.costBasis()).isEqualTo("1894.50");
        assertThat(dto.currentPrice()).isNull();
        assertThat(dto.marketValue()).isNull();
        assertThat(dto.unrealizedPnL()).isNull();
        assertThat(dto.unrealizedPnLPct()).isNull();
    }

    // ─── toBalanceResponse ──────────────────────────────────────────────────────

    @Test
    void toBalanceResponse_happyPath() {
        Instant created = Instant.parse("2026-05-01T10:00:00Z");
        Instant updated = Instant.parse("2026-05-24T20:15:33Z");
        UserBalance balance = newBalance(new BigDecimal("8345.67"), "USD", created, updated);

        BalanceResponse response = mapper.toBalanceResponse(balance);

        assertThat(response.balance()).isEqualTo("8345.67");
        assertThat(response.currency()).isEqualTo("USD");
        assertThat(response.lastUpdatedAt()).isEqualTo(updated);
    }

    @Test
    void toBalanceResponse_fallsBackToCreatedAtWhenUpdatedAtNull() {
        Instant created = Instant.parse("2026-05-01T10:00:00Z");
        UserBalance balance = newBalance(new BigDecimal("10000.00"), "USD", created, null);

        BalanceResponse response = mapper.toBalanceResponse(balance);

        assertThat(response.lastUpdatedAt()).isEqualTo(created);
    }

    // ─── toPendingOrderDto ──────────────────────────────────────────────────────

    @Test
    void toPendingOrderDto_mapsAllFields() {
        UUID orderId = UUID.randomUUID();
        UUID clientOrderId = UUID.randomUUID();
        Order order =
                Order.newPending(
                        UUID.randomUUID(),
                        clientOrderId,
                        "TSLA",
                        OrderSide.BUY,
                        OrderType.MARKET,
                        3,
                        new BigDecimal("235.1000"),
                        new BigDecimal("14.1100"),
                        new BigDecimal("719.4100"));
        setField(order, "id", orderId);
        setField(order, "submittedAt", Instant.parse("2026-05-24T22:30:15Z"));

        PendingOrderDto dto = mapper.toPendingOrderDto(order);

        assertThat(dto.orderId()).isEqualTo(orderId);
        assertThat(dto.clientOrderId()).isEqualTo(clientOrderId);
        assertThat(dto.ticker()).isEqualTo("TSLA");
        assertThat(dto.side()).isEqualTo(OrderSide.BUY);
        assertThat(dto.quantity()).isEqualTo(3);
        assertThat(dto.submittedAt()).isEqualTo(Instant.parse("2026-05-24T22:30:15Z"));
        assertThat(dto.quotedTotal()).isEqualTo("719.41");
    }

    // ─── toPositionsResponse — marketDataAvailable lógica ───────────────────────

    @Test
    void toPositionsResponse_emptyPositions_returnsTrueAndEmptyArrays() {
        PortfolioPositionsResponse response =
                mapper.toPositionsResponse(List.of(), Map.of(), List.of());

        assertThat(response.positions()).isEmpty();
        assertThat(response.pendingOrders()).isEmpty();
        assertThat(response.marketDataAvailable()).isEqualTo("true");
        assertThat(response.fetchedAt()).isNotNull();
    }

    @Test
    void toPositionsResponse_allPricesPresent_marketDataAvailableTrue() {
        List<Position> positions =
                List.of(
                        newPosition("AAPL", 10, new BigDecimal("189.4500")),
                        newPosition("MSFT", 5, new BigDecimal("412.0000")));
        Map<String, BigDecimal> prices = new HashMap<>();
        prices.put("AAPL", new BigDecimal("193.2000"));
        prices.put("MSFT", new BigDecimal("408.5000"));

        PortfolioPositionsResponse response =
                mapper.toPositionsResponse(positions, prices, List.of());

        assertThat(response.marketDataAvailable()).isEqualTo("true");
        assertThat(response.positions()).hasSize(2);
        assertThat(response.positions().get(0).currentPrice()).isEqualTo("193.20");
    }

    @Test
    void toPositionsResponse_allPricesNull_marketDataAvailableFalse() {
        List<Position> positions =
                List.of(
                        newPosition("AAPL", 10, new BigDecimal("189.4500")),
                        newPosition("MSFT", 5, new BigDecimal("412.0000")));
        Map<String, BigDecimal> prices = new HashMap<>();
        prices.put("AAPL", null);
        prices.put("MSFT", null);

        PortfolioPositionsResponse response =
                mapper.toPositionsResponse(positions, prices, List.of());

        assertThat(response.marketDataAvailable()).isEqualTo("false");
        assertThat(response.positions()).allMatch(p -> p.currentPrice() == null);
    }

    @Test
    void toPositionsResponse_partialPrices_marketDataAvailablePartial() {
        List<Position> positions =
                List.of(
                        newPosition("AAPL", 10, new BigDecimal("189.4500")),
                        newPosition("MSFT", 5, new BigDecimal("412.0000")));
        Map<String, BigDecimal> prices = new HashMap<>();
        prices.put("AAPL", new BigDecimal("193.2000"));
        prices.put("MSFT", null);

        PortfolioPositionsResponse response =
                mapper.toPositionsResponse(positions, prices, List.of());

        assertThat(response.marketDataAvailable()).isEqualTo("partial");
        assertThat(response.positions().get(0).currentPrice()).isEqualTo("193.20");
        assertThat(response.positions().get(1).currentPrice()).isNull();
    }

    // ─── helpers ────────────────────────────────────────────────────────────────

    private static Position newPosition(String ticker, int quantity, BigDecimal avgBuyPrice) {
        return Position.newPosition(UUID.randomUUID(), ticker, quantity, avgBuyPrice);
    }

    private static UserBalance newBalance(
            BigDecimal amount, String currency, Instant createdAt, Instant updatedAt) {
        UserBalance balance = UserBalance.initial(UUID.randomUUID());
        setField(balance, "balance", amount);
        setField(balance, "currency", currency);
        setField(balance, "createdAt", createdAt);
        setField(balance, "updatedAt", updatedAt);
        return balance;
    }

    /** Setea campos privados gestionados por Hibernate (id, createdAt, updatedAt) en tests. */
    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = findField(target.getClass(), fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("No se pudo setear " + fieldName, e);
        }
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}
