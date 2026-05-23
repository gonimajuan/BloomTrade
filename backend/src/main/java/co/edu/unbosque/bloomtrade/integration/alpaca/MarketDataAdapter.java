package co.edu.unbosque.bloomtrade.integration.alpaca;

import co.edu.unbosque.bloomtrade.integration.alpaca.dto.AlpacaLatestQuoteResponse;
import io.github.resilience4j.retry.annotation.Retry;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Adapter síncrono hacia la API de Market Data de Alpaca (HU-F09 D9 D-MD-PROVIDER).
 *
 * <p>Reemplaza al previsto {@code MarketDataAdapter} sobre Polygon. Endpoint:
 * {@code GET /v2/stocks/{symbol}/quotes/latest} en {@code data.alpaca.markets}. La cuenta
 * paper trading da acceso gratuito a Market Data IEX feed (delayed 15min) — suficiente
 * para demo MVP.
 *
 * <p>{@code @Retry(name="alpacaDataApi")}: 3 intentos sobre {@link HttpServerErrorException}
 * y {@link ResourceAccessException}. 429 rate limit es transitorio pero
 * {@link HttpClientErrorException} no se reintenta por config — un 429 sostenido propaga
 * como {@link MarketDataUnavailableException} (D6: log diferenciado WARN vs ERROR).
 */
@Component
public class MarketDataAdapter {

    private static final Logger log = LoggerFactory.getLogger(MarketDataAdapter.class);

    private final RestClient restClient;

    public MarketDataAdapter(@Qualifier("alpacaDataRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Obtiene el mid-price del ticker como {@code (ask + bid) / 2}.
     *
     * @throws MarketDataUnavailableException si Alpaca Data caída tras retries, 429 rate limit,
     *     respuesta sin {@code quote}, o bid/ask en cero.
     */
    @Retry(name = "alpacaDataApi", fallbackMethod = "getLatestPriceFallback")
    public BigDecimal getLatestPrice(String ticker) {
        try {
            AlpacaLatestQuoteResponse response =
                    restClient
                            .get()
                            .uri("/v2/stocks/{symbol}/quotes/latest", ticker)
                            .retrieve()
                            .body(AlpacaLatestQuoteResponse.class);

            if (response == null) {
                throw new MarketDataUnavailableException(
                        "Alpaca Data devolvió body vacío para " + ticker);
            }
            BigDecimal midPrice = response.getMidPrice();
            log.debug("Alpaca Data ticker={} midPrice={}", ticker, midPrice);
            return midPrice;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                log.warn(
                        "Alpaca Data 429 rate-limited ticker={} — HU-F18 cache amortiguará en post-MVP",
                        ticker);
                throw new MarketDataUnavailableException(
                        "Alpaca Data rate-limited (429). Intenta en unos segundos.", e);
            }
            log.error(
                    "Alpaca Data 4xx ticker={}: {} {}",
                    ticker,
                    e.getStatusCode(),
                    e.getResponseBodyAsString());
            throw new MarketDataUnavailableException(
                    "Alpaca Data " + e.getStatusCode() + " para ticker " + ticker, e);
        }
    }

    /**
     * Fallback de {@link #getLatestPrice} invocado por Resilience4j tras agotar 3 retries
     * sobre {@link HttpServerErrorException} o {@link ResourceAccessException}.
     */
    @SuppressWarnings("unused") // invocado por Resilience4j vía reflection
    private BigDecimal getLatestPriceFallback(String ticker, Throwable t) {
        if (t instanceof MarketDataUnavailableException mdue) {
            throw mdue;
        }
        log.error("Alpaca Data tras 3 retries ticker={}: {}", ticker, t.getMessage());
        throw new MarketDataUnavailableException(
                "Alpaca Data no respondió tras 3 intentos para " + ticker, t);
    }
}
