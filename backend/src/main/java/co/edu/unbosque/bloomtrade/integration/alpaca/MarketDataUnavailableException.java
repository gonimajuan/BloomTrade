package co.edu.unbosque.bloomtrade.integration.alpaca;

/**
 * No se pudo obtener el precio actual de un ticker (HU-F09 Lote B). Casos: Alpaca Data API
 * caída tras retries, 429 rate limit, respuesta con bid+ask en cero (mercado cerrado).
 *
 * <p>El {@code GlobalExceptionHandler} mapea a HTTP 502 {@code MARKET_DATA_UNAVAILABLE}.
 * HU-F18 cache amortiguará casos transitorios; MVP fail-fast (D6).
 */
public class MarketDataUnavailableException extends RuntimeException {

    public MarketDataUnavailableException(String message) {
        super(message);
    }

    public MarketDataUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
