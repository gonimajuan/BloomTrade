package co.edu.unbosque.bloomtrade.integration.alpaca;

import co.edu.unbosque.bloomtrade.integration.alpaca.dto.AlpacaOrderRequest;
import co.edu.unbosque.bloomtrade.integration.alpaca.dto.AlpacaOrderResponse;
import co.edu.unbosque.bloomtrade.trading.dto.CancelOutcome;
import co.edu.unbosque.bloomtrade.trading.service.SubmitMarketOrderCommand;
import io.github.resilience4j.retry.annotation.Retry;
import java.math.BigDecimal;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
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
    private final long cancelPollingIntervalMs;
    private final int cancelPollingMaxAttempts;

    public AlpacaTradingAdapter(
            @Qualifier("alpacaTradingRestClient") RestClient restClient,
            @Value("${trading.cancel.polling.interval-ms:200}") long cancelPollingIntervalMs,
            @Value("${trading.cancel.polling.max-attempts:10}") int cancelPollingMaxAttempts) {
        this.restClient = restClient;
        this.cancelPollingIntervalMs = cancelPollingIntervalMs;
        this.cancelPollingMaxAttempts = cancelPollingMaxAttempts;
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

    // ─── HU-F15 — Cancelar orden con polling canónico async (D4) ──────────────

    /**
     * Cancela una orden encolada en Alpaca con patrón polling canónico (HU-F15 D4):
     * {@code DELETE /v2/orders/{id}} + polling {@code GET /v2/orders/{id}} cada
     * {@code interval-ms} hasta {@code max-attempts} veces o hasta ver estado terminal.
     *
     * <p>Outcomes posibles ({@link CancelOutcome}):
     * <ul>
     *   <li>{@link CancelOutcome.Canceled} — Alpaca confirmó {@code status=canceled}.</li>
     *   <li>{@link CancelOutcome.PendingCancel} — polling agotó sin estado terminal.</li>
     *   <li>{@link CancelOutcome.RaceFilled} — Alpaca respondió {@code filled} durante el
     *       polling (D17 D-RACE-FILLED-UX): se ejecutó justo antes de la cancelación.</li>
     * </ul>
     *
     * <p>El {@code DELETE} inicial está bajo {@code @Retry(alpacaTradingApi)} para 5xx/timeout.
     * El loop de polling NO está bajo retry — su tolerancia a errores transitorios es loguear
     * y continuar al siguiente poll (el timeout total acota el peor caso).
     *
     * @throws AlpacaOrderNotFoundException si Alpaca devuelve 404 al DELETE (drift detectado).
     * @throws AlpacaOrderNotCancelableException si Alpaca devuelve 422 (ya filled/canceled).
     * @throws AlpacaUnexpectedStatusException si el polling encuentra un status no contemplado
     *     (ej. {@code partially_filled} D19).
     * @throws AlpacaApiException ante 5xx persistentes / timeouts tras los 3 retries del DELETE.
     */
    @Retry(name = "alpacaTradingApi", fallbackMethod = "cancelOrderFallback")
    public CancelOutcome cancelOrder(String alpacaOrderId) {
        // 1. DELETE /v2/orders/{id}
        try {
            restClient
                    .delete()
                    .uri("/v2/orders/{id}", alpacaOrderId)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Alpaca aceptó DELETE para orden alpacaId={}", alpacaOrderId);
        } catch (HttpClientErrorException e) {
            HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
            if (status == HttpStatus.NOT_FOUND) {
                throw new AlpacaOrderNotFoundException(alpacaOrderId);
            }
            if (status == HttpStatus.UNPROCESSABLE_ENTITY) {
                throw new AlpacaOrderNotCancelableException(alpacaOrderId);
            }
            log.error(
                    "Alpaca 4xx inesperado en DELETE /v2/orders/{}: {} {}",
                    alpacaOrderId,
                    e.getStatusCode(),
                    e.getResponseBodyAsString());
            throw new AlpacaApiException(
                    "Alpaca rechazó DELETE: " + e.getStatusCode(), 1, e);
        }

        // 2. Polling GET /v2/orders/{id} hasta estado terminal o timeout.
        for (int attempt = 1; attempt <= cancelPollingMaxAttempts; attempt++) {
            try {
                Thread.sleep(cancelPollingIntervalMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new AlpacaApiException(
                        "Polling interrumpido para alpacaId=" + alpacaOrderId, attempt, ie);
            }

            AlpacaOrderResponse snapshot;
            try {
                snapshot =
                        restClient
                                .get()
                                .uri("/v2/orders/{id}", alpacaOrderId)
                                .retrieve()
                                .body(AlpacaOrderResponse.class);
            } catch (HttpServerErrorException | ResourceAccessException transientErr) {
                // Tolerar errores transitorios mid-polling: log + continuar al siguiente poll.
                log.warn(
                        "Polling cancel GET falló transitoriamente attempt={}/{}: {}",
                        attempt,
                        cancelPollingMaxAttempts,
                        transientErr.getMessage());
                continue;
            } catch (HttpClientErrorException e) {
                if (HttpStatus.valueOf(e.getStatusCode().value()) == HttpStatus.NOT_FOUND) {
                    // Alpaca dice que la orden ya no existe — drift; tratar igual que en el DELETE.
                    throw new AlpacaOrderNotFoundException(alpacaOrderId);
                }
                throw new AlpacaApiException(
                        "Alpaca 4xx en polling GET /v2/orders/" + alpacaOrderId + ": "
                                + e.getStatusCode(),
                        attempt,
                        e);
            }

            if (snapshot == null) {
                log.warn("Polling cancel devolvió body vacío attempt={}", attempt);
                continue;
            }

            // Estados terminales esperados.
            if (snapshot.isCanceled()) {
                Instant canceledAt = parseTimestamp(snapshot.canceledAt());
                log.info(
                        "Alpaca confirmó canceled para alpacaId={} en attempt={}",
                        alpacaOrderId,
                        attempt);
                return new CancelOutcome.Canceled(canceledAt);
            }
            if (snapshot.isFilled()) {
                int filledQty = parseIntSafe(snapshot.filledQty());
                Instant filledAt = parseTimestamp(snapshot.filledAt());
                log.warn(
                        "RACE_FILLED detectado para alpacaId={}: la orden se ejecutó durante el cancel polling (filled_avg_price={}, filled_qty={})",
                        alpacaOrderId,
                        snapshot.filledAvgPrice(),
                        filledQty);
                return new CancelOutcome.RaceFilled(
                        snapshot.filledAvgPrice(), filledQty, filledAt);
            }
            // Estados no esperados durante un cancel inmediato.
            if (snapshot.isPartiallyFilled() || snapshot.isExpired() || snapshot.isRejected()) {
                throw new AlpacaUnexpectedStatusException(alpacaOrderId, snapshot.status());
            }
            // Status no terminal (accepted, new, pending_cancel, etc.) — continuar polling.
        }

        // 3. Polling agotó sin estado terminal — PENDING_CANCEL (reconcile lazy v2 lo materializará).
        log.info(
                "Polling cancel timeout para alpacaId={} tras {} intentos — devolviendo PENDING_CANCEL",
                alpacaOrderId,
                cancelPollingMaxAttempts);
        return new CancelOutcome.PendingCancel("POLLING_TIMEOUT");
    }

    /**
     * Fallback de {@link #cancelOrder} invocado por Resilience4j tras 3 retries fallidos sobre
     * el DELETE inicial (típicamente 5xx persistentes / network). Las excepciones tipadas
     * (drift 404/422, unexpected status) se re-lanzan tal cual; los errores transitorios
     * remanentes se envuelven en {@link AlpacaApiException}.
     */
    @SuppressWarnings("unused") // invocado por Resilience4j vía reflection
    private CancelOutcome cancelOrderFallback(String alpacaOrderId, Throwable t) {
        if (t instanceof AlpacaOrderNotFoundException nf) {
            throw nf;
        }
        if (t instanceof AlpacaOrderNotCancelableException nc) {
            throw nc;
        }
        if (t instanceof AlpacaUnexpectedStatusException us) {
            throw us;
        }
        if (t instanceof AlpacaApiException aa) {
            throw aa;
        }
        log.error(
                "Alpaca cancelOrder tras 3 retries id={}: {}", alpacaOrderId, t.getMessage());
        throw new AlpacaApiException(
                "Alpaca API no respondió tras 3 intentos en DELETE /v2/orders/" + alpacaOrderId,
                3,
                t);
    }

    private static Instant parseTimestamp(String iso) {
        if (iso == null || iso.isBlank()) {
            return Instant.now();
        }
        try {
            return Instant.parse(iso);
        } catch (Exception e) {
            // Defensive: si Alpaca devuelve un formato no-ISO, usar now() como aproximación.
            return Instant.now();
        }
    }

    private static int parseIntSafe(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            // Alpaca puede devolver "5.0" o "5" según el endpoint; usar BigDecimal por seguridad.
            return new BigDecimal(value).intValueExact();
        } catch (Exception e) {
            return 0;
        }
    }
}
