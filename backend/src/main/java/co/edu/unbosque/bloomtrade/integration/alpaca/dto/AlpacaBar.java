package co.edu.unbosque.bloomtrade.integration.alpaca.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Barra OHLCV intradía devuelta por Alpaca Data API endpoint
 * {@code GET /v2/stocks/{symbol}/bars} (HU-F18 Lote A).
 *
 * <p>Shape:
 * <pre>{@code
 * { "t": "2026-05-24T13:30:00Z",  // timestamp (start of bar)
 *   "o": 189.50,                  // open
 *   "h": 189.80,                  // high
 *   "l": 189.30,                  // low
 *   "c": 189.60,                  // close
 *   "v": 1500000,                 // volume
 *   "n": 12345,                   // trade count (ignored)
 *   "vw": 189.55                  // VWAP (ignored)
 * }
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlpacaBar(
        @JsonProperty("t") Instant timestamp,
        @JsonProperty("o") BigDecimal open,
        @JsonProperty("h") BigDecimal high,
        @JsonProperty("l") BigDecimal low,
        @JsonProperty("c") BigDecimal close,
        @JsonProperty("v") Long volume) {}
