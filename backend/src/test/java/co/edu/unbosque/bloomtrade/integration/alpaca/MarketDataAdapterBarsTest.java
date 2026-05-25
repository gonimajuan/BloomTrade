package co.edu.unbosque.bloomtrade.integration.alpaca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import co.edu.unbosque.bloomtrade.dashboard.dto.IntradayBar;
import java.math.BigDecimal;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Unit tests del nuevo {@code MarketDataAdapter.getIntradayBars} (HU-F18 Lote A).
 *
 * <p>Sin Spring context — el comportamiento de retry sobre 5xx queda para
 * {@code DashboardControllerIT} con {@code @SpringBootTest} (necesario para {@code @Retry} AOP).
 */
class MarketDataAdapterBarsTest {

    private static final String DATA_BASE_URL = "https://data.alpaca.markets";

    private MockRestServiceServer mockServer;
    private MarketDataAdapter adapter;

    @BeforeEach
    void setup() {
        RestClient.Builder builder = RestClient.builder().baseUrl(DATA_BASE_URL);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        adapter = new MarketDataAdapter(restClient);
    }

    @Test
    void getIntradayBars_happyPath_returnsParsedBars() {
        String responseBody =
                """
                {
                  "bars": {
                    "AAPL": [
                      { "t": "2026-05-25T13:30:00Z", "o": 189.50, "h": 189.80, "l": 189.30, "c": 189.60, "v": 1500000 },
                      { "t": "2026-05-25T13:45:00Z", "o": 189.60, "h": 190.10, "l": 189.55, "c": 190.05, "v": 1320000 },
                      { "t": "2026-05-25T14:00:00Z", "o": 190.05, "h": 190.50, "l": 190.00, "c": 190.45, "v": 1610000 }
                    ]
                  },
                  "next_page_token": null
                }
                """;
        mockServer
                .expect(
                        requestTo(
                                Matchers.startsWith(
                                        DATA_BASE_URL + "/v2/stocks/AAPL/bars?timeframe=15Min")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        List<IntradayBar> bars = adapter.getIntradayBars("AAPL");

        assertThat(bars).hasSize(3);
        assertThat(bars.get(0).open()).isEqualByComparingTo(new BigDecimal("189.50"));
        assertThat(bars.get(0).close()).isEqualByComparingTo(new BigDecimal("189.60"));
        assertThat(bars.get(0).volume()).isEqualTo(1_500_000L);
        assertThat(bars.get(2).close()).isEqualByComparingTo(new BigDecimal("190.45"));
        mockServer.verify();
    }

    @Test
    void getIntradayBars_emptyBarsForTicker_returnsEmptyList() {
        String responseBody = "{\"bars\":{},\"next_page_token\":null}";
        mockServer
                .expect(
                        requestTo(
                                Matchers.startsWith(
                                        DATA_BASE_URL + "/v2/stocks/WOW/bars?timeframe=15Min")))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        List<IntradayBar> bars = adapter.getIntradayBars("WOW");

        assertThat(bars).isEmpty();
    }

    @Test
    void getIntradayBars_404_throwsMarketDataUnavailable() {
        mockServer
                .expect(
                        requestTo(
                                Matchers.startsWith(
                                        DATA_BASE_URL + "/v2/stocks/UNKNOWN/bars?timeframe=15Min")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).body("{\"message\":\"not found\"}"));

        assertThatThrownBy(() -> adapter.getIntradayBars("UNKNOWN"))
                .isInstanceOf(MarketDataUnavailableException.class)
                .hasMessageContaining("404");
    }

    @Test
    void getIntradayBars_429RateLimited_throwsMarketDataUnavailable() {
        mockServer
                .expect(
                        requestTo(
                                Matchers.startsWith(
                                        DATA_BASE_URL + "/v2/stocks/AAPL/bars?timeframe=15Min")))
                .andRespond(
                        withStatus(HttpStatus.TOO_MANY_REQUESTS)
                                .body("{\"message\":\"rate limited\"}"));

        assertThatThrownBy(() -> adapter.getIntradayBars("AAPL"))
                .isInstanceOf(MarketDataUnavailableException.class)
                .hasMessageContaining("rate-limited");
    }
}
