package co.edu.unbosque.bloomtrade.portfolio.web;

import co.edu.unbosque.bloomtrade.portfolio.domain.Position;
import co.edu.unbosque.bloomtrade.portfolio.domain.UserBalance;
import co.edu.unbosque.bloomtrade.portfolio.dto.BalanceResponse;
import co.edu.unbosque.bloomtrade.portfolio.dto.PendingOrderDto;
import co.edu.unbosque.bloomtrade.portfolio.dto.PortfolioPositionsResponse;
import co.edu.unbosque.bloomtrade.portfolio.dto.PositionDto;
import co.edu.unbosque.bloomtrade.trading.domain.Order;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Mapeo manual del módulo Portfolio (HU-F16+F21 Lote C — plan D15).
 *
 * <p>Por qué manual y no MapStruct: además de la stringificación de {@link BigDecimal}
 * (mismo motivo que {@code OrderMapper}), aquí hay cálculos compuestos —
 * {@code marketValue}, {@code unrealizedPnL}, {@code unrealizedPnLPct} — que serían
 * boilerplate {@code @Mapping(expression = "...")} en MapStruct. La aritmética inline es
 * más legible.
 *
 * <p>Plan D10: TODOS los stringificados con {@code scale=2} y {@link RoundingMode#HALF_UP}.
 * Los cálculos intermedios mantienen {@code scale=4} para preservar precisión.
 * Plan D11: {@code avgCost} es el promedio puro de compras (sin commissions);
 * {@code unrealizedPnL} es bruto (sin descontar commission de venta hipotética).
 * Plan D14: {@code currency} en {@code PositionDto} hardcoded "USD" (Position no tiene
 * columna currency); en {@code BalanceResponse} leído desde {@code UserBalance.currency}.
 */
@Component
public class PortfolioMapper {

    private static final int DISPLAY_SCALE = 2;
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final String DEFAULT_CURRENCY = "USD";
    private static final String MD_TRUE = "true";
    private static final String MD_FALSE = "false";
    private static final String MD_PARTIAL = "partial";

    public BalanceResponse toBalanceResponse(UserBalance entity) {
        Instant lastUpdated =
                entity.getUpdatedAt() != null ? entity.getUpdatedAt() : entity.getCreatedAt();
        return new BalanceResponse(
                toScale2String(entity.getBalance()), entity.getCurrency(), lastUpdated);
    }

    public PendingOrderDto toPendingOrderDto(Order order) {
        return new PendingOrderDto(
                order.getId(),
                order.getClientOrderId(),
                order.getTicker(),
                order.getSide(),
                order.getQuantity(),
                order.getSubmittedAt(),
                toScale2String(order.getQuotedTotal()));
    }

    /**
     * Construye un {@link PositionDto} con o sin mark-to-market. Cuando
     * {@code currentPriceOrNull} es {@code null}, los 4 campos de mercado son {@code null}
     * (plan D3).
     */
    public PositionDto toPositionDto(Position p, BigDecimal currentPriceOrNull) {
        String avgCost = toScale2String(p.getAvgBuyPrice());
        BigDecimal costBasisRaw = p.getAvgBuyPrice().multiply(BigDecimal.valueOf(p.getQuantity()));
        String costBasis = toScale2String(costBasisRaw);

        if (currentPriceOrNull == null) {
            return new PositionDto(
                    p.getTicker(),
                    p.getQuantity(),
                    avgCost,
                    costBasis,
                    DEFAULT_CURRENCY,
                    null,
                    null,
                    null,
                    null);
        }

        BigDecimal marketValueRaw =
                currentPriceOrNull.multiply(BigDecimal.valueOf(p.getQuantity()));
        BigDecimal unrealizedPnLRaw = marketValueRaw.subtract(costBasisRaw);
        // costBasisRaw > 0 garantizado (Position.incrementBy exige avgBuyPrice > 0 y qty > 0)
        BigDecimal unrealizedPnLPctRaw =
                unrealizedPnLRaw
                        .multiply(ONE_HUNDRED)
                        .divide(costBasisRaw, 4, RoundingMode.HALF_UP);

        return new PositionDto(
                p.getTicker(),
                p.getQuantity(),
                avgCost,
                costBasis,
                DEFAULT_CURRENCY,
                toScale2String(currentPriceOrNull),
                toScale2String(marketValueRaw),
                toScale2String(unrealizedPnLRaw),
                toScale2String(unrealizedPnLPctRaw));
    }

    public PortfolioPositionsResponse toPositionsResponse(
            List<Position> positions,
            Map<String, BigDecimal> prices,
            List<Order> pendingOrders) {
        List<PositionDto> positionDtos =
                positions.stream().map(p -> toPositionDto(p, prices.get(p.getTicker()))).toList();
        List<PendingOrderDto> pendingDtos = pendingOrders.stream().map(this::toPendingOrderDto).toList();
        String marketDataAvailable = computeMarketDataAvailable(positions, prices);
        return new PortfolioPositionsResponse(
                positionDtos, pendingDtos, marketDataAvailable, Instant.now());
    }

    private static String computeMarketDataAvailable(
            List<Position> positions, Map<String, BigDecimal> prices) {
        if (positions.isEmpty()) {
            return MD_TRUE;
        }
        long nulls = positions.stream().filter(p -> prices.get(p.getTicker()) == null).count();
        if (nulls == 0) {
            return MD_TRUE;
        }
        if (nulls == positions.size()) {
            return MD_FALSE;
        }
        return MD_PARTIAL;
    }

    private static String toScale2String(BigDecimal value) {
        return value == null
                ? null
                : value.setScale(DISPLAY_SCALE, RoundingMode.HALF_UP).toPlainString();
    }
}
