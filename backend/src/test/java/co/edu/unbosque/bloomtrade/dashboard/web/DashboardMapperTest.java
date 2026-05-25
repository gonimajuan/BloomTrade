package co.edu.unbosque.bloomtrade.dashboard.web;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.unbosque.bloomtrade.auth.profile.domain.Market;
import co.edu.unbosque.bloomtrade.dashboard.dto.IntradayBar;
import co.edu.unbosque.bloomtrade.dashboard.dto.MarketGroupDto;
import co.edu.unbosque.bloomtrade.dashboard.dto.TickerDashboardDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests unit del {@link DashboardMapper} sin Spring (HU-F18 Lote B).
 *
 * <p>Cubre cálculo de {@code dayChangePct}, manejo null de currentPrice/bars, sparkline
 * stringification scale=2, agrupación por mercado y construcción del response.
 */
class DashboardMapperTest {

    private DashboardMapper mapper;

    @BeforeEach
    void setup() {
        mapper = new DashboardMapper();
    }

    private static IntradayBar bar(BigDecimal open, BigDecimal close) {
        return new IntradayBar(Instant.parse("2026-05-25T13:30:00Z"), open, BigDecimal.ZERO, BigDecimal.ZERO, close, 0L);
    }

    @Test
    void toTickerDashboardDto_happyPath_calculatesDayChangePct() {
        // current 193.20, open 189.50 → diff 3.70, pct = 3.70 / 189.50 × 100 ≈ 1.95
        BigDecimal current = new BigDecimal("193.20");
        List<IntradayBar> bars = List.of(
                bar(new BigDecimal("189.50"), new BigDecimal("190.10")),
                bar(new BigDecimal("190.10"), new BigDecimal("191.20")),
                bar(new BigDecimal("191.20"), new BigDecimal("193.20")));

        TickerDashboardDto dto = mapper.toTickerDashboardDto("AAPL", current, bars);

        assertThat(dto.ticker()).isEqualTo("AAPL");
        assertThat(dto.currentPrice()).isEqualTo("193.20");
        assertThat(dto.openPrice()).isEqualTo("189.50");
        assertThat(dto.dayChangePct()).isEqualTo("1.95");
        assertThat(dto.sparkline()).containsExactly("190.10", "191.20", "193.20");
    }

    @Test
    void toTickerDashboardDto_priceNull_marketFieldsAreNull_butSparklineFromBars() {
        // Si currentPrice falla pero bars sí están: openPrice viene de bars,
        // currentPrice/dayChangePct son null por cascada, sparkline poblada.
        List<IntradayBar> bars = List.of(
                bar(new BigDecimal("100.00"), new BigDecimal("101.00")),
                bar(new BigDecimal("101.00"), new BigDecimal("102.00")));

        TickerDashboardDto dto = mapper.toTickerDashboardDto("AAPL", null, bars);

        assertThat(dto.currentPrice()).isNull();
        assertThat(dto.openPrice()).isEqualTo("100.00");
        assertThat(dto.dayChangePct()).isNull(); // currentPrice null → cascada
        assertThat(dto.sparkline()).containsExactly("101.00", "102.00");
    }

    @Test
    void toTickerDashboardDto_emptyBars_openPriceAndDayChangePctNullAndSparklineEmpty() {
        BigDecimal current = new BigDecimal("193.20");

        TickerDashboardDto dto = mapper.toTickerDashboardDto("WOW", current, List.of());

        assertThat(dto.currentPrice()).isEqualTo("193.20");
        assertThat(dto.openPrice()).isNull();
        assertThat(dto.dayChangePct()).isNull();
        assertThat(dto.sparkline()).isEmpty();
    }

    @Test
    void toTickerDashboardDto_sparklineValuesAreScale2() {
        // Open / close con scale=4 desde Alpaca → sparkline scale=2 HALF_UP.
        List<IntradayBar> bars = List.of(
                bar(new BigDecimal("189.5012"), new BigDecimal("189.5678")),
                bar(new BigDecimal("189.5678"), new BigDecimal("190.0049"))); // .0049 → 190.00

        TickerDashboardDto dto = mapper.toTickerDashboardDto("AAPL", new BigDecimal("190.00"), bars);

        assertThat(dto.openPrice()).isEqualTo("189.50"); // 189.5012 → 189.50
        assertThat(dto.sparkline()).containsExactly("189.57", "190.00");
    }

    @Test
    void toTickerDashboardDto_openPriceZero_dayChangePctNullToAvoidDivByZero() {
        List<IntradayBar> bars = List.of(bar(BigDecimal.ZERO, new BigDecimal("10.00")));

        TickerDashboardDto dto = mapper.toTickerDashboardDto("X", new BigDecimal("10.00"), bars);

        assertThat(dto.openPrice()).isEqualTo("0.00");
        assertThat(dto.dayChangePct()).isNull();
    }

    @Test
    void toMarketGroupDto_wrapsItemsUnderMarketName() {
        TickerDashboardDto t1 = new TickerDashboardDto("AAPL", null, null, null, List.of());

        MarketGroupDto group = mapper.toMarketGroupDto(Market.NYSE, List.of(t1));

        assertThat(group.market()).isEqualTo("NYSE");
        assertThat(group.items()).containsExactly(t1);
    }
}
