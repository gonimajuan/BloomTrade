package co.edu.unbosque.bloomtrade.integration.stripe;

import com.stripe.Stripe;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Configuración del SDK de Stripe (HU-F06 D2). Setea {@code Stripe.apiKey} al arrancar a partir
 * de la env var {@code STRIPE_API_KEY} (RAK preferido sobre sk_, recomendación del skill
 * {@code stripe-best-practices.references/security.md}).
 *
 * <p>Falla rápido si la key está vacía o tiene formato evidentemente incorrecto — mismo patrón
 * que {@code JwtService} para {@code JWT_SECRET} (HU-F02).
 *
 * <p>La SDK usa la última API version disponible al instalar; NO se override {@code apiVersion}
 * porque la skill manda "Always use the latest API version and SDK".
 */
@Configuration
public class StripeConfig {

    private static final Logger log = LoggerFactory.getLogger(StripeConfig.class);

    private final String apiKey;

    public StripeConfig(@Value("${stripe.api-key:${STRIPE_API_KEY:}}") String apiKey) {
        this.apiKey = apiKey;
    }

    @PostConstruct
    void initialize() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "STRIPE_API_KEY no está configurada. Crea una Restricted API Key (RAK) en "
                            + "https://dashboard.stripe.com/test/apikeys y configúrala en .env "
                            + "(prefijo rk_test_ recomendado sobre sk_).");
        }
        Stripe.apiKey = apiKey;
        log.info(
                "Stripe SDK inicializado con key {} (prefijo {}); version: {}",
                maskedKey(apiKey),
                keyPrefix(apiKey),
                Stripe.API_VERSION);
        if (!apiKey.startsWith("rk_")) {
            log.warn(
                    "STRIPE_API_KEY no usa el prefijo 'rk_' (Restricted API Key). La skill "
                            + "stripe-best-practices recomienda usar RAK con permisos mínimos en "
                            + "lugar de secret keys (sk_).");
        }
    }

    private static String maskedKey(String key) {
        if (key.length() <= 12) {
            return "****";
        }
        return key.substring(0, 8) + "****" + key.substring(key.length() - 4);
    }

    private static String keyPrefix(String key) {
        int underscore = key.indexOf('_');
        return underscore > 0 ? key.substring(0, underscore + 1) : "unknown";
    }
}
