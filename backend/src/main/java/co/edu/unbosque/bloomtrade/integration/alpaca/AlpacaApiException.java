package co.edu.unbosque.bloomtrade.integration.alpaca;

/**
 * Error técnico al llamar a Alpaca: timeouts, 5xx persistentes tras retries, network errors
 * (HU-F09 Lote B).
 *
 * <p>NO se usa para rechazos lógicos de Alpaca (ej: símbolo no soportado, qty inválida) —
 * para eso ver {@link AlpacaOrderRejectedException}. El {@code GlobalExceptionHandler} mapea
 * esta a HTTP 502 {@code ALPACA_API_ERROR}.
 *
 * <p>{@code attempts}: cuántas veces se intentó antes de propagar (típicamente 3 tras
 * {@code @Retry(name="alpacaTradingApi")} con backoff 1s/3s/9s).
 */
public class AlpacaApiException extends RuntimeException {

    private final int attempts;

    public AlpacaApiException(String message, int attempts, Throwable cause) {
        super(message, cause);
        this.attempts = attempts;
    }

    public AlpacaApiException(String message, int attempts) {
        super(message);
        this.attempts = attempts;
    }

    public int getAttempts() {
        return attempts;
    }
}
