package co.edu.unbosque.bloomtrade.integration.alpaca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Unit tests del {@link MarketDataAdapter} sin Spring context — happy path + 429 rate limit +
 * 4xx error + respuesta con bid/ask en cero.
 *
 * <p>El comportamiento de retry sobre 5xx/timeouts queda para {@code TradingControllerIT} del
 * Lote G con {@code @SpringBootTest} (necesario para {@code @Retry} AOP).
 */
class MarketDataAdapterTest {

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
    void getLatestPrice_happyPath_returnsMidPriceScale4() {
        String responseBody =
                """
                {
                  "symbol": "AAPL",
                  "quote": {
                    "ap": 184.52,
                    "as": 100,
                    "bp": 184.48,
                    "bs": 200,
                    "t": "2026-05-22T14:32:25Z"
                  }
                }
                """;
        mockServer
                .expect(requestTo(DATA_BASE_URL + "/v2/stocks/AAPL/quotes/latest"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        BigDecimal midPrice = adapter.getLatestPrice("AAPL");

        // (184.52 + 184.48) / 2 = 184.5000 — scale 4 HALF_UP.
        assertThat(midPrice).isEqualByComparingTo(new BigDecimal("184.5000"));
        assertThat(midPrice.scale()).isEqualTo(4);
        mockServer.verify();
    }

    @Test
    void getLatestPrice_oddPriceDivision_appliesHalfUpAtScale4() {
        // (184.50 + 184.49) / 2 = 184.495 (truncated to scale 4 with HALF_UP = 184.4950).
        String responseBody =
                """
                {
                  "symbol": "MSFT",
                  "quote": {
                    "ap": 184.50,
                    "bp": 184.49,
                    "as": 50,
                    "bs": 50,
                    "t": "2026-05-22T14:32:25Z"
                  }
                }
                """;
        mockServer
                .expect(requestTo(DATA_BASE_URL + "/v2/stocks/MSFT/quotes/latest"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        BigDecimal midPrice = adapter.getLatestPrice("MSFT");

        assertThat(midPrice).isEqualByComparingTo(new BigDecimal("184.4950"));
    }

    @Test
    void getLatestPrice_429RateLimited_throwsMarketDataUnavailable() {
        mockServer
                .expect(requestTo(DATA_BASE_URL + "/v2/stocks/AAPL/quotes/latest"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("{\"message\":\"rate limited\"}"));

        assertThatThrownBy(() -> adapter.getLatestPrice("AAPL"))
                .isInstanceOf(MarketDataUnavailableException.class)
                .hasMessageContaining("rate-limited");
    }

    @Test
    void getLatestPrice_404NotFound_throwsMarketDataUnavailable() {
        mockServer
                .expect(requestTo(DATA_BASE_URL + "/v2/stocks/UNKNOWN/quotes/latest"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).body("{\"message\":\"not found\"}"));

        assertThatThrownBy(() -> adapter.getLatestPrice("UNKNOWN"))
                .isInstanceOf(MarketDataUnavailableException.class)
                .hasMessageContaining("404");
    }

    @Test
    void getLatestPrice_bidAskBothZero_throwsMarketDataUnavailable() {
        String responseBody =
                """
                {
                  "symbol": "TSLA",
                  "quote": {
                    "ap": 0,
                    "bp": 0,
                    "as": 0,
                    "bs": 0,
                    "t": "2026-05-22T14:32:25Z"
                  }
                }
                """;
        mockServer
                .expect(requestTo(DATA_BASE_URL + "/v2/stocks/TSLA/quotes/latest"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.getLatestPrice("TSLA"))
                .isInstanceOf(MarketDataUnavailableException.class)
                .hasMessageContaining("cero");
    }

    @Test
    void getLatestPrice_onlyAskValid_returnsAskAsMid() {
        // Si bid es 0/null pero ask es válido, retorna ask (mismo si solo bid).
        String responseBody =
                """
                {
                  "symbol": "AAPL",
                  "quote": {
                    "ap": 184.50,
                    "bp": 0,
                    "as": 100,
                    "bs": 0,
                    "t": "2026-05-22T14:32:25Z"
                  }
                }
                """;
        mockServer
                .expect(requestTo(DATA_BASE_URL + "/v2/stocks/AAPL/quotes/latest"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        BigDecimal midPrice = adapter.getLatestPrice("AAPL");

        assertThat(midPrice).isEqualByComparingTo(new BigDecimal("184.5000"));
    }
}
