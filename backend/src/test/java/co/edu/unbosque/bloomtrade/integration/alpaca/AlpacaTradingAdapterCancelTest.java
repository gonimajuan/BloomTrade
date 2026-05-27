package co.edu.unbosque.bloomtrade.integration.alpaca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import co.edu.unbosque.bloomtrade.trading.dto.CancelOutcome;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Unit tests del cancelOrder de {@link AlpacaTradingAdapter} (HU-F15 Lote A T1.20).
 *
 * <p>Cubre los 3 outcomes ({@link CancelOutcome.Canceled}, {@link CancelOutcome.PendingCancel},
 * {@link CancelOutcome.RaceFilled}) + las 3 excepciones (404, 422, partially_filled).
 *
 * <p>Polling con interval-ms muy bajo (5ms) y max-attempts pequeño (3) para que los tests
 * con timeout completen rápido. Sin Spring AOP — {@code @Retry} no aplica.
 */
class AlpacaTradingAdapterCancelTest {

    private static final String ALPACA_BASE_URL = "https://paper-api.alpaca.markets";
    private static final String ALPACA_ORDER_ID = "alp-xyz-123";

    private MockRestServiceServer mockServer;
    private AlpacaTradingAdapter adapter;

    @BeforeEach
    void setup() {
        RestClient.Builder builder = RestClient.builder().baseUrl(ALPACA_BASE_URL);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        // Polling rápido para tests: 5ms × 3 = 15ms total worst-case timeout.
        adapter = new AlpacaTradingAdapter(restClient, 5L, 3);
    }

    @Test
    void cancelOrder_happyPath_returnsCanceledQuickly() {
        // DELETE 204 No Content
        mockServer
                .expect(requestTo(ALPACA_BASE_URL + "/v2/orders/" + ALPACA_ORDER_ID))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        // GET attempt #1 devuelve status=canceled
        String pollResponse =
                """
                {
                  "id": "alp-xyz-123",
                  "status": "canceled",
                  "canceled_at": "2026-05-26T15:42:25Z"
                }
                """;
        mockServer
                .expect(requestTo(ALPACA_BASE_URL + "/v2/orders/" + ALPACA_ORDER_ID))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(pollResponse, MediaType.APPLICATION_JSON));

        CancelOutcome outcome = adapter.cancelOrder(ALPACA_ORDER_ID);

        assertThat(outcome).isInstanceOf(CancelOutcome.Canceled.class);
        CancelOutcome.Canceled canceled = (CancelOutcome.Canceled) outcome;
        assertThat(canceled.alpacaCanceledAt()).isNotNull();
        mockServer.verify();
    }

    @Test
    void cancelOrder_pollingTimeout_returnsPendingCancel() {
        mockServer
                .expect(requestTo(ALPACA_BASE_URL + "/v2/orders/" + ALPACA_ORDER_ID))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        // GET responde "accepted" en los 3 attempts → timeout.
        String acceptedBody =
                """
                { "id": "alp-xyz-123", "status": "accepted" }
                """;
        mockServer
                .expect(ExpectedCount.times(3), requestTo(ALPACA_BASE_URL + "/v2/orders/" + ALPACA_ORDER_ID))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(acceptedBody, MediaType.APPLICATION_JSON));

        CancelOutcome outcome = adapter.cancelOrder(ALPACA_ORDER_ID);

        assertThat(outcome).isInstanceOf(CancelOutcome.PendingCancel.class);
        CancelOutcome.PendingCancel pending = (CancelOutcome.PendingCancel) outcome;
        assertThat(pending.reason()).isEqualTo("POLLING_TIMEOUT");
        mockServer.verify();
    }

    @Test
    void cancelOrder_raceFilledDuringPoll_returnsRaceFilled() {
        mockServer
                .expect(requestTo(ALPACA_BASE_URL + "/v2/orders/" + ALPACA_ORDER_ID))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        // GET attempt #1: "filled" con filled_avg_price y filled_qty.
        String filledResponse =
                """
                {
                  "id": "alp-xyz-123",
                  "status": "filled",
                  "filled_avg_price": "198.50",
                  "filled_qty": "5",
                  "filled_at": "2026-05-26T15:42:30Z"
                }
                """;
        mockServer
                .expect(requestTo(ALPACA_BASE_URL + "/v2/orders/" + ALPACA_ORDER_ID))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(filledResponse, MediaType.APPLICATION_JSON));

        CancelOutcome outcome = adapter.cancelOrder(ALPACA_ORDER_ID);

        assertThat(outcome).isInstanceOf(CancelOutcome.RaceFilled.class);
        CancelOutcome.RaceFilled race = (CancelOutcome.RaceFilled) outcome;
        assertThat(race.filledAvgPrice()).isEqualByComparingTo(new BigDecimal("198.50"));
        assertThat(race.filledQty()).isEqualTo(5);
        assertThat(race.alpacaFilledAt()).isNotNull();
        mockServer.verify();
    }

    @Test
    void cancelOrder_alpacaReturns404OnDelete_throwsOrderNotFound() {
        mockServer
                .expect(requestTo(ALPACA_BASE_URL + "/v2/orders/" + ALPACA_ORDER_ID))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> adapter.cancelOrder(ALPACA_ORDER_ID))
                .isInstanceOf(AlpacaOrderNotFoundException.class)
                .hasMessageContaining(ALPACA_ORDER_ID);
        mockServer.verify();
    }

    @Test
    void cancelOrder_alpacaReturns422OnDelete_throwsOrderNotCancelable() {
        mockServer
                .expect(requestTo(ALPACA_BASE_URL + "/v2/orders/" + ALPACA_ORDER_ID))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));

        assertThatThrownBy(() -> adapter.cancelOrder(ALPACA_ORDER_ID))
                .isInstanceOf(AlpacaOrderNotCancelableException.class)
                .hasMessageContaining(ALPACA_ORDER_ID);
        mockServer.verify();
    }

    @Test
    void cancelOrder_pollingPartiallyFilled_throwsUnexpectedStatus() {
        mockServer
                .expect(requestTo(ALPACA_BASE_URL + "/v2/orders/" + ALPACA_ORDER_ID))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        String partialResponse =
                """
                {
                  "id": "alp-xyz-123",
                  "status": "partially_filled",
                  "filled_qty": "3"
                }
                """;
        mockServer
                .expect(requestTo(ALPACA_BASE_URL + "/v2/orders/" + ALPACA_ORDER_ID))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(partialResponse, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.cancelOrder(ALPACA_ORDER_ID))
                .isInstanceOf(AlpacaUnexpectedStatusException.class)
                .hasMessageContaining("partially_filled");
        mockServer.verify();
    }

    @Test
    void cancelOrder_alpacaReturns404OnGet_throwsOrderNotFound() {
        // DELETE OK; durante el polling, Alpaca devuelve 404 (la orden desapareció).
        mockServer
                .expect(requestTo(ALPACA_BASE_URL + "/v2/orders/" + ALPACA_ORDER_ID))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        mockServer
                .expect(requestTo(ALPACA_BASE_URL + "/v2/orders/" + ALPACA_ORDER_ID))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> adapter.cancelOrder(ALPACA_ORDER_ID))
                .isInstanceOf(AlpacaOrderNotFoundException.class);
        mockServer.verify();
    }
}
