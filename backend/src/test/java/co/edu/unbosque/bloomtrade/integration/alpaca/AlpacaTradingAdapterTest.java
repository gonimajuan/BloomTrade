package co.edu.unbosque.bloomtrade.integration.alpaca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import co.edu.unbosque.bloomtrade.integration.alpaca.dto.AlpacaOrderResponse;
import co.edu.unbosque.bloomtrade.trading.domain.OrderSide;
import co.edu.unbosque.bloomtrade.trading.service.SubmitMarketOrderCommand;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Unit tests del {@link AlpacaTradingAdapter} sin Spring context — {@link MockRestServiceServer}
 * intercepta el {@link RestClient}. Cubre happy path (filled), rejected, y 4xx (creds/request
 * malformado).
 *
 * <p><strong>Nota:</strong> sin Spring AOP, {@code @Retry} no se aplica. Los tests de retry
 * sobre 5xx y timeouts viven en {@code TradingControllerIT} del Lote G con {@code @SpringBootTest}
 * + WireMock real.
 */
class AlpacaTradingAdapterTest {

    private static final UUID CLIENT_ORDER_ID = UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d479");
    private static final String ALPACA_BASE_URL = "https://paper-api.alpaca.markets";

    private MockRestServiceServer mockServer;
    private AlpacaTradingAdapter adapter;

    @BeforeEach
    void setup() {
        RestClient.Builder builder = RestClient.builder().baseUrl(ALPACA_BASE_URL);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        // HU-F15: constructor extendido con polling config — usar defaults prod (200ms × 10).
        // Estos tests no ejercitan cancelOrder; el polling no se invoca.
        adapter = new AlpacaTradingAdapter(restClient, 200L, 10);
    }

    @Test
    void submitMarketOrder_happyPathFilled_returnsResponseWithFilledAvgPrice() {
        String responseBody =
                """
                {
                  "id": "alpaca-uuid-1",
                  "client_order_id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
                  "symbol": "AAPL",
                  "qty": "10",
                  "side": "buy",
                  "type": "market",
                  "status": "filled",
                  "filled_avg_price": "184.62",
                  "filled_qty": "10",
                  "submitted_at": "2026-05-22T14:32:25Z",
                  "filled_at": "2026-05-22T14:32:25Z"
                }
                """;
        mockServer
                .expect(requestTo(ALPACA_BASE_URL + "/v2/orders"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.qty").value("10"))
                .andExpect(jsonPath("$.side").value("buy"))
                .andExpect(jsonPath("$.type").value("market"))
                .andExpect(jsonPath("$.time_in_force").value("day"))
                .andExpect(jsonPath("$.client_order_id").value(CLIENT_ORDER_ID.toString()))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        AlpacaOrderResponse response =
                adapter.submitMarketOrder(
                        new SubmitMarketOrderCommand(CLIENT_ORDER_ID, "AAPL", 10, OrderSide.BUY));

        assertThat(response.id()).isEqualTo("alpaca-uuid-1");
        assertThat(response.isFilled()).isTrue();
        assertThat(response.filledAvgPrice()).isEqualByComparingTo(new BigDecimal("184.62"));
        assertThat(response.filledQty()).isEqualTo("10");
        mockServer.verify();
    }

    @Test
    void submitMarketOrder_accepted_returnsResponseNonTerminalForPolling() {
        String responseBody =
                """
                {
                  "id": "alpaca-uuid-2",
                  "client_order_id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
                  "symbol": "AAPL",
                  "qty": "10",
                  "side": "buy",
                  "type": "market",
                  "status": "accepted",
                  "filled_avg_price": null,
                  "filled_qty": "0"
                }
                """;
        mockServer
                .expect(requestTo(ALPACA_BASE_URL + "/v2/orders"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        AlpacaOrderResponse response =
                adapter.submitMarketOrder(
                        new SubmitMarketOrderCommand(CLIENT_ORDER_ID, "AAPL", 10, OrderSide.BUY));

        assertThat(response.isFilled()).isFalse();
        assertThat(response.isRejected()).isFalse();
        assertThat(response.isTerminal()).isFalse();
        assertThat(response.filledAvgPrice()).isNull();
    }

    @Test
    void submitMarketOrder_rejected_throwsAlpacaOrderRejectedException() {
        String responseBody =
                """
                {
                  "id": "alpaca-uuid-3",
                  "client_order_id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
                  "symbol": "AAPL",
                  "qty": "10",
                  "side": "buy",
                  "type": "market",
                  "status": "rejected",
                  "rejected_reason": "qty exceeds buying power"
                }
                """;
        mockServer
                .expect(requestTo(ALPACA_BASE_URL + "/v2/orders"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        assertThatThrownBy(
                        () ->
                                adapter.submitMarketOrder(
                                        new SubmitMarketOrderCommand(
                                                CLIENT_ORDER_ID, "AAPL", 10, OrderSide.BUY)))
                .isInstanceOf(AlpacaOrderRejectedException.class)
                .hasMessageContaining("qty exceeds buying power")
                .extracting("alpacaReason", "alpacaOrderId")
                .containsExactly("qty exceeds buying power", "alpaca-uuid-3");
    }

    @Test
    void submitMarketOrder_4xxBadRequest_throwsAlpacaApiExceptionWithAttempts1() {
        mockServer
                .expect(requestTo(ALPACA_BASE_URL + "/v2/orders"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withBadRequest().body("{\"message\":\"invalid qty\"}"));

        assertThatThrownBy(
                        () ->
                                adapter.submitMarketOrder(
                                        new SubmitMarketOrderCommand(
                                                CLIENT_ORDER_ID, "AAPL", 10, OrderSide.BUY)))
                .isInstanceOf(AlpacaApiException.class)
                .hasMessageContaining("400")
                .extracting("attempts")
                .isEqualTo(1);
    }

    @Test
    void submitMarketOrder_4xx401Unauthorized_throwsAlpacaApiException() {
        mockServer
                .expect(requestTo(ALPACA_BASE_URL + "/v2/orders"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{\"message\":\"invalid key\"}"));

        assertThatThrownBy(
                        () ->
                                adapter.submitMarketOrder(
                                        new SubmitMarketOrderCommand(
                                                CLIENT_ORDER_ID, "AAPL", 10, OrderSide.BUY)))
                .isInstanceOf(AlpacaApiException.class);
    }

    @Test
    void getOrder_happyPath_returnsResponse() {
        String responseBody =
                """
                {
                  "id": "alpaca-uuid-4",
                  "client_order_id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
                  "symbol": "AAPL",
                  "status": "filled",
                  "filled_avg_price": "184.50",
                  "filled_qty": "10"
                }
                """;
        mockServer
                .expect(requestTo(ALPACA_BASE_URL + "/v2/orders/alpaca-uuid-4"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        AlpacaOrderResponse response = adapter.getOrder("alpaca-uuid-4");

        assertThat(response.isFilled()).isTrue();
        assertThat(response.filledAvgPrice()).isEqualByComparingTo(new BigDecimal("184.50"));
    }
}
