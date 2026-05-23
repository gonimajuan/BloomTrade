package co.edu.unbosque.bloomtrade.integration.alpaca.dto;

import co.edu.unbosque.bloomtrade.integration.alpaca.MarketDataUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Respuesta de {@code GET /v2/stocks/{symbol}/quotes/latest} de Alpaca Data API.
 *
 * <p>Shape:
 * <pre>{@code
 * { "symbol": "AAPL",
 *   "quote": {
 *     "ap": 184.52,  // ask price
 *     "as": 100,     // ask size
 *     "bp": 184.48,  // bid price
 *     "bs": 200,     // bid size
 *     "t": "..."     // timestamp
 *   }
 * }
 * }</pre>
 *
 * <p>{@link #getMidPrice} calcula {@code (ap + bp) / 2} con scale=4 HALF_UP. Si ambos son 0
 * (mercado cerrado completo, sin quotes recientes) lanza {@link MarketDataUnavailableException}
 * — el caller propaga 502.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlpacaLatestQuoteResponse(String symbol, Quote quote) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Quote(
            @JsonProperty("ap") BigDecimal askPrice,
            @JsonProperty("bp") BigDecimal bidPrice,
            @JsonProperty("as") Integer askSize,
            @JsonProperty("bs") Integer bidSize,
            @JsonProperty("t") String timestamp) {}

    /**
     * Mid-price calculado como (ask + bid) / 2 con scale=4 HALF_UP. Si bid o ask es 0/null,
     * usa el otro lado. Si ambos son 0/null, lanza {@link MarketDataUnavailableException}.
     */
    public BigDecimal getMidPrice() {
        if (quote == null) {
            throw new MarketDataUnavailableException(
                    "Respuesta de Alpaca Data sin objeto 'quote' para " + symbol);
        }
        BigDecimal ask = quote.askPrice();
        BigDecimal bid = quote.bidPrice();
        boolean askValid = ask != null && ask.signum() > 0;
        boolean bidValid = bid != null && bid.signum() > 0;

        if (askValid && bidValid) {
            return ask.add(bid).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
        }
        if (askValid) {
            return ask.setScale(4, RoundingMode.HALF_UP);
        }
        if (bidValid) {
            return bid.setScale(4, RoundingMode.HALF_UP);
        }
        throw new MarketDataUnavailableException(
                "Alpaca Data devolvió bid y ask en cero para " + symbol
                        + " — mercado cerrado o ticker sin cotización reciente");
    }
}
