package co.edu.unbosque.bloomtrade.integration.alpaca;

import co.edu.unbosque.bloomtrade.integration.alpaca.dto.AlpacaOrderRequest;
import co.edu.unbosque.bloomtrade.integration.alpaca.dto.AlpacaOrderResponse;
import co.edu.unbosque.bloomtrade.trading.service.SubmitMarketOrderCommand;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Adapter síncrono hacia la API de trading de Alpaca (HU-F09 Lote B).
 *
 * <p>TAC-M1 (intermediario) + TAC-I2 (adaptar interfaz) + TAC-D2 (RetryPolicy):
 * {@link #submitMarketOrder} y {@link #getOrder} se anotan con
 * {@code @Retry(name="alpacaTradingApi")} configurado en {@code application.yml} con 3
 * intentos de backoff exponencial 1s/3s/9s. Reintenta solo en errores transitorios:
 * {@link HttpServerErrorException} (5xx) y {@link ResourceAccessException} (timeouts /
 * network). {@link AlpacaOrderRejectedException} está en {@code ignore-exceptions} — no
 * tiene sentido reintentar un rechazo lógico.
 *
 * <p>La traducción JSON ↔ POJO la hace Jackson via {@link RestClient}. {@link AlpacaOrderRequest}
 * lleva los campos snake_case con {@code @JsonProperty}; {@link AlpacaOrderResponse} tolera
 * campos desconocidos del wire format.
 */
@Component
public class AlpacaTradingAdapter {

    private static final Logger log = LoggerFactory.getLogger(AlpacaTradingAdapter.class);

    private final RestClient restClient;

    public AlpacaTradingAdapter(@Qualifier("alpacaTradingRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Envía una market order a Alpaca paper. Retorna la respuesta inicial — para market orders
     * típicamente {@code status=accepted} o {@code status=filled} dentro de &lt;500ms.
     * El caller ({@code TradingService}) decide si polear via {@link #getOrder} si el status
     * inicial es no-terminal.
     *
     * @throws AlpacaOrderRejectedException si Alpaca respondió {@code status=rejected}.
     * @throws AlpacaApiException ante 4xx no-rejected (creds inválidas, request malformado),
     *     o tras 3 retries fallidos sobre 5xx/timeouts.
     */
    @Retry(name = "alpacaTradingApi", fallbackMethod = "submitMarketOrderFallback")
    public AlpacaOrderResponse submitMarketOrder(SubmitMarketOrderCommand command) {
        AlpacaOrderRequest body = AlpacaOrderRequest.market(
                command.ticker(),
                command.quantity(),
                command.side().name(),
                command.clientOrderId());

        try {
            AlpacaOrderResponse response =
                    restClient
                            .post()
                            .uri("/v2/orders")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .body(body)
                            .retrieve()
                            .body(AlpacaOrderResponse.class);

            if (response == null) {
                throw new AlpacaApiException(
                        "Alpaca devolvió body vacío en POST /v2/orders", 1);
            }
            if (response.isRejected()) {
                String reason = response.rejectedReason() != null
                        ? response.rejectedReason()
                        : "sin razón especificada";
                log.warn(
                        "Alpaca rechazó orden clientOrderId={} ticker={}: {}",
                        command.clientOrderId(),
                        command.ticker(),
                        reason);
                throw new AlpacaOrderRejectedException(reason, response.id());
            }
            log.info(
                    "Alpaca aceptó orden clientOrderId={} alpacaId={} status={}",
                    command.clientOrderId(),
                    response.id(),
                    response.status());
            return response;
        } catch (HttpClientErrorException e) {
            log.error(
                    "Alpaca 4xx en POST /v2/orders clientOrderId={}: {} {}",
                    command.clientOrderId(),
                    e.getStatusCode(),
                    e.getResponseBodyAsString());
            throw new AlpacaApiException(
                    "Alpaca rechazó request: " + e.getStatusCode(), 1, e);
        }
    }

    /**
     * Consulta el estado actual de una orden por su {@code alpacaOrderId}. Usado para polling
     * tras un {@code status=accepted} inicial (el {@code TradingService} decide cuándo).
     */
    @Retry(name = "alpacaTradingApi", fallbackMethod = "getOrderFallback")
    public AlpacaOrderResponse getOrder(String alpacaOrderId) {
        try {
            return restClient
                    .get()
                    .uri("/v2/orders/{id}", alpacaOrderId)
                    .retrieve()
                    .body(AlpacaOrderResponse.class);
        } catch (HttpClientErrorException e) {
            throw new AlpacaApiException(
                    "Alpaca 4xx en GET /v2/orders/" + alpacaOrderId + ": " + e.getStatusCode(),
                    1,
                    e);
        }
    }

    /**
     * Fallback de {@link #submitMarketOrder} invocado por Resilience4j tras agotar 3 retries
     * sobre {@link HttpServerErrorException} o {@link ResourceAccessException}. Re-lanza tal
     * cual las excepciones ya tipadas (4xx, rejected) que llegaron acá por casualidad;
     * envuelve los errores transitorios remanentes en {@link AlpacaApiException} con
     * {@code attempts=3}.
     */
    @SuppressWarnings("unused") // invocado por Resilience4j vía reflection
    private AlpacaOrderResponse submitMarketOrderFallback(
            SubmitMarketOrderCommand command, Throwable t) {
        if (t instanceof AlpacaOrderRejectedException ar) {
            throw ar;
        }
        if (t instanceof AlpacaApiException aa) {
            throw aa;
        }
        log.error(
                "Alpaca trading tras 3 retries clientOrderId={}: {}",
                command.clientOrderId(),
                t.getMessage());
        throw new AlpacaApiException(
                "Alpaca API no respondió tras 3 intentos: " + t.getMessage(), 3, t);
    }

    @SuppressWarnings("unused") // invocado por Resilience4j vía reflection
    private AlpacaOrderResponse getOrderFallback(String alpacaOrderId, Throwable t) {
        if (t instanceof AlpacaApiException aa) {
            throw aa;
        }
        log.error("Alpaca getOrder tras 3 retries id={}: {}", alpacaOrderId, t.getMessage());
        throw new AlpacaApiException(
                "Alpaca API no respondió tras 3 intentos en GET /v2/orders/" + alpacaOrderId,
                3,
                t);
    }
}
