package co.edu.unbosque.bloomtrade.shared.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Resuelve el texto humano (ES) a partir de un código de error en SCREAMING_SNAKE_CASE
 * (decisión D10 del plan HU-F01). Respaldo: {@code classpath:validation-messages.properties}.
 *
 * <p>Carga única en memoria; si falta una clave devuelve el propio código (nunca {@code null}).
 */
public final class ValidationMessages {

    private static final String RESOURCE = "/validation-messages.properties";
    private static final Properties MESSAGES = load();

    private ValidationMessages() {}

    private static Properties load() {
        Properties props = new Properties();
        try (InputStream is = ValidationMessages.class.getResourceAsStream(RESOURCE)) {
            if (is == null) {
                throw new IllegalStateException("No se encontró " + RESOURCE + " en el classpath");
            }
            props.load(new InputStreamReader(is, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo cargar " + RESOURCE, e);
        }
        return props;
    }

    /** Mensaje humano para el código; si no existe, devuelve el código tal cual. */
    public static String humanFor(String code) {
        return MESSAGES.getProperty(code, code);
    }
}
