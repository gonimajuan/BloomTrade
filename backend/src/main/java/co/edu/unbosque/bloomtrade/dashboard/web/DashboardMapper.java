package co.edu.unbosque.bloomtrade.dashboard.web;

import co.edu.unbosque.bloomtrade.auth.profile.domain.Market;
import co.edu.unbosque.bloomtrade.dashboard.dto.AccountEquityDto;
import co.edu.unbosque.bloomtrade.dashboard.dto.DashboardSnapshotResponse;
import co.edu.unbosque.bloomtrade.dashboard.dto.IntradayBar;
import co.edu.unbosque.bloomtrade.dashboard.dto.MarketGroupDto;
import co.edu.unbosque.bloomtrade.dashboard.dto.TickerDashboardDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Mapper manual del módulo Dashboard (HU-F18 Lote B — plan D14).
 *
 * <p>Decisión de mapeo manual sobre MapStruct: consistente con todos los mappers previos
 * (F09/F10/F16+F21). Los cálculos compuestos ({@code dayChangePct} con división segura
 * BigDecimal scale=2 HALF_UP) son más legibles imperativos que con expressions MapStruct.
 *
 * <p>Convenciones de null:
 * <ul>
 *   <li>{@code currentPrice} null → {@code dayChangePct} también null por cascada.</li>
 *   <li>{@code bars} vacío o null → {@code openPrice}/{@code dayChangePct} null, sparkline vacía.</li>
 *   <li>{@code openPrice} con {@code signum()==0} → {@code dayChangePct} null (evita división por cero).</li>
 * </ul>
 */
@Component
public class DashboardMapper {

    /**
     * Construye el DTO de un ticker individual. Plan D17: {@code openPrice} viene del
     * {@code open} de la PRIMERA barra (cronológicamente más antigua). Plan D11: sparkline
     * vacía cuando no hay bars — el frontend renderiza "—" en vez de LineChart vacío.
     */
    public TickerDashboardDto toTickerDashboardDto(
            String ticker, BigDecimal currentPrice, List<IntradayBar> bars) {
        String currentPriceStr = stringifyScale2(currentPrice);
        BigDecimal openPrice =
                (bars == null || bars.isEmpty()) ? null : bars.get(0).open();
        String openPriceStr = stringifyScale2(openPrice);
        String dayChangePctStr = computeDayChangePct(currentPrice, openPrice);
        List<String> sparkline = mapSparkline(bars);
        return new TickerDashboardDto(
                ticker, currentPriceStr, openPriceStr, dayChangePctStr, sparkline);
    }

    public MarketGroupDto toMarketGroupDto(Market market, List<TickerDashboardDto> items) {
        return new MarketGroupDto(market.name(), items);
    }

    public DashboardSnapshotResponse toDashboardSnapshotResponse(
            List<MarketGroupDto> groups,
            AccountEquityDto equity,
            String marketDataAvailable,
            Instant fetchedAt) {
        return new DashboardSnapshotResponse(groups, equity, marketDataAvailable, fetchedAt);
    }

    /** {@code ((current − open) / open) × 100} scale=2 HALF_UP. null-safe + zero-safe. */
    private String computeDayChangePct(BigDecimal current, BigDecimal open) {
        if (current == null || open == null || open.signum() == 0) {
            return null;
        }
        BigDecimal diff = current.subtract(open);
        BigDecimal pct =
                diff.multiply(BigDecimal.valueOf(100))
                        .divide(open, 2, RoundingMode.HALF_UP);
        return pct.toPlainString();
    }

    private List<String> mapSparkline(List<IntradayBar> bars) {
        if (bars == null || bars.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> closes = new ArrayList<>(bars.size());
        for (IntradayBar bar : bars) {
            closes.add(stringifyScale2(bar.close()));
        }
        return closes;
    }

    private String stringifyScale2(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
