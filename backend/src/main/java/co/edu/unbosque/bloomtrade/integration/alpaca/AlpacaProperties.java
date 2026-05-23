package co.edu.unbosque.bloomtrade.integration.alpaca;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Holder de configuración Alpaca leído de {@code application.yml} bloque {@code alpaca:}.
 * Patrón {@code @Value} directo (consistente con {@code StripeConfig} de HU-F06; el repo no
 * usa {@code @ConfigurationProperties} todavía — evitamos introducir esa convención solo
 * para una integración).
 *
 * <p>La validación de presencia de creds (no blank, no placeholder) la hace
 * {@link IntegrationConfig#validateCredentials} al arrancar.
 */
@Component
public class AlpacaProperties {

    private final String apiKey;
    private final String apiSecret;
    private final String tradingBaseUrl;
    private final String dataBaseUrl;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    public AlpacaProperties(
            @Value("${alpaca.api-key:}") String apiKey,
            @Value("${alpaca.api-secret:}") String apiSecret,
            @Value("${alpaca.trading-base-url:https://paper-api.alpaca.markets}") String tradingBaseUrl,
            @Value("${alpaca.data-base-url:https://data.alpaca.markets}") String dataBaseUrl,
            @Value("${alpaca.connect-timeout:2s}") Duration connectTimeout,
            @Value("${alpaca.read-timeout:5s}") Duration readTimeout) {
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.tradingBaseUrl = tradingBaseUrl;
        this.dataBaseUrl = dataBaseUrl;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getApiSecret() {
        return apiSecret;
    }

    public String getTradingBaseUrl() {
        return tradingBaseUrl;
    }

    public String getDataBaseUrl() {
        return dataBaseUrl;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }
}
