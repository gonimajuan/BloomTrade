package co.edu.unbosque.bloomtrade.integration.alpaca;

import co.edu.unbosque.bloomtrade.dashboard.dto.IntradayBar;
import co.edu.unbosque.bloomtrade.integration.alpaca.dto.AlpacaBar;
import co.edu.unbosque.bloomtrade.integration.alpaca.dto.AlpacaBarsResponse;
import co.edu.unbosque.bloomtrade.integration.alpaca.dto.AlpacaLatestQuoteResponse;
import io.github.resilience4j.retry.annotation.Retry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
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

    /**
     * Obtiene las barras recientes ({@code timeframe=15Min}) para renderizar sparklines en el
     * dashboard (HU-F18 Lote A — plan D5 + D18).
     *
     * <p>URI: {@code GET /v2/stocks/{symbol}/bars?timeframe=15Min&start={now-7d}&limit=50&adjustment=raw&feed=iex}.
     * Devuelve hasta 50 barras de los últimos 7 días — Alpaca entrega las más recientes dentro
     * de la ventana, así que si el mercado lleva cerrado un weekend o feriado, las barras del
     * último día abierto siguen visibles. Cambio Día 10 polish: ventana fija de hoy hacía que
     * los sparklines estuvieran vacíos fuera de horario US (Bug 2 del audit single-user).
     *
     * <p>Si Alpaca no encuentra barras (ticker delisted o no cubierto por Alpaca, p.ej. LSE/TSE/
     * ASX en paper free tier), retorna lista vacía — el caller decide cómo mostrar el sparkline
     * vacío.
     *
     * @throws MarketDataUnavailableException si Alpaca Data cae tras 3 retries, 429, o 404/4xx.
     */
    @Retry(name = "alpacaDataApi", fallbackMethod = "getIntradayBarsFallback")
    public List<IntradayBar> getIntradayBars(String ticker) {
        String start = Instant.now().minus(Duration.ofDays(7)).toString();
        try {
            AlpacaBarsResponse response =
                    restClient
                            .get()
                            .uri(
                                    uriBuilder ->
                                            uriBuilder
                                                    .path("/v2/stocks/{symbol}/bars")
                                                    .queryParam("timeframe", "15Min")
                                                    .queryParam("start", start)
                                                    .queryParam("limit", 50)
                                                    .queryParam("adjustment", "raw")
                                                    .queryParam("feed", "iex")
                                                    .build(ticker))
                            .retrieve()
                            .body(AlpacaBarsResponse.class);

            if (response == null
                    || response.bars() == null
                    || response.bars().isEmpty()) {
                log.debug("Alpaca Data bars ticker={} sin barras en ventana", ticker);
                return Collections.emptyList();
            }
            List<IntradayBar> bars =
                    response.bars().stream()
                            .map(
                                    b ->
                                            new IntradayBar(
                                                    b.timestamp(),
                                                    b.open(),
                                                    b.high(),
                                                    b.low(),
                                                    b.close(),
                                                    b.volume()))
                            .toList();
            log.debug("Alpaca Data bars ticker={} count={}", ticker, bars.size());
            return bars;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                log.warn(
                        "Alpaca Data bars 429 rate-limited ticker={} — considerar D-SPARKLINE-CACHE V2",
                        ticker);
                throw new MarketDataUnavailableException(
                        "Alpaca Data bars rate-limited (429) para " + ticker, e);
            }
            log.error(
                    "Alpaca Data bars 4xx ticker={}: {} {}",
                    ticker,
                    e.getStatusCode(),
                    e.getResponseBodyAsString());
            throw new MarketDataUnavailableException(
                    "Alpaca Data bars " + e.getStatusCode() + " para " + ticker, e);
        }
    }

    /**
     * Fallback de {@link #getIntradayBars} invocado por Resilience4j tras agotar 3 retries.
     */
    @SuppressWarnings("unused") // invocado por Resilience4j vía reflection
    private List<IntradayBar> getIntradayBarsFallback(String ticker, Throwable t) {
        if (t instanceof MarketDataUnavailableException mdue) {
            throw mdue;
        }
        log.error("Alpaca Data bars tras 3 retries ticker={}: {}", ticker, t.getMessage());
        throw new MarketDataUnavailableException(
                "Alpaca Data bars no respondió tras 3 intentos para " + ticker, t);
    }
}
