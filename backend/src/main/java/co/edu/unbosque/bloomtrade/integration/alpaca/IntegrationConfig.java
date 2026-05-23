package co.edu.unbosque.bloomtrade.integration.alpaca;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Configuración de los {@link RestClient} hacia Alpaca (HU-F09 Lote B).
 *
 * <p>D9 D-MD-PROVIDER + D20: dos {@link RestClient} con distinto {@code baseUrl} (trading vs
 * data) pero MISMAS credenciales (Alpaca Paper Trading account usa los mismos headers
 * {@code APCA-API-KEY-ID} / {@code APCA-API-SECRET-KEY} en ambas APIs). Cada {@code RestClient}
 * tiene su propio request factory con timeouts compartidos vía {@link AlpacaProperties}.
 *
 * <p>Fail-fast en {@link #validateCredentials} si las creds están vacías o quedaron como
 * placeholder {@code replace_me} — mismo patrón que {@code StripeConfig} de HU-F06.
 */
@Configuration
public class IntegrationConfig {

    private static final Logger log = LoggerFactory.getLogger(IntegrationConfig.class);

    private final AlpacaProperties properties;

    public IntegrationConfig(AlpacaProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void validateCredentials() {
        if (isBlankOrPlaceholder(properties.getApiKey())) {
            throw new IllegalStateException(
                    "ALPACA_API_KEY no está configurada. Crea una cuenta paper en "
                            + "https://app.alpaca.markets/paper/dashboard/overview y genera "
                            + "API Keys (Key ID + Secret) en la sección API Keys.");
        }
        if (isBlankOrPlaceholder(properties.getApiSecret())) {
            throw new IllegalStateException(
                    "ALPACA_API_SECRET no está configurada. Generar junto con ALPACA_API_KEY.");
        }
        log.info(
                "Alpaca integration inicializada — trading: {} | data: {} | key: {}",
                properties.getTradingBaseUrl(),
                properties.getDataBaseUrl(),
                maskedKey(properties.getApiKey()));
    }

    @Bean
    public RestClient alpacaTradingRestClient() {
        return buildRestClient(properties.getTradingBaseUrl());
    }

    @Bean
    public RestClient alpacaDataRestClient() {
        return buildRestClient(properties.getDataBaseUrl());
    }

    private RestClient buildRestClient(String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("APCA-API-KEY-ID", properties.getApiKey())
                .defaultHeader("APCA-API-SECRET-KEY", properties.getApiSecret())
                .requestFactory(buildRequestFactory(
                        properties.getConnectTimeout(), properties.getReadTimeout()))
                .build();
    }

    private static ClientHttpRequestFactory buildRequestFactory(Duration connect, Duration read) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connect.toMillis());
        factory.setReadTimeout((int) read.toMillis());
        return factory;
    }

    private static boolean isBlankOrPlaceholder(String value) {
        return value == null || value.isBlank() || "replace_me".equals(value);
    }

    private static String maskedKey(String key) {
        if (key == null || key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
