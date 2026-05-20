package co.edu.unbosque.bloomtrade.auth.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * Generador de OTP de 6 dígitos (spec HU-F02 §5.1 paso 11, decisión D4 del plan).
 *
 * <p>Usa {@link SecureRandom} (no {@code Math.random}) y comparación timing-safe vía
 * {@link MessageDigest#isEqual} para evitar canales laterales por tiempo de ejecución.
 */
@Component
public class OtpGenerator {

    private static final int OTP_BOUND = 1_000_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Genera un OTP de exactamente 6 dígitos numéricos con padding a la izquierda. */
    public String generate() {
        int value = RANDOM.nextInt(OTP_BOUND);
        return String.format("%06d", value);
    }

    /**
     * Comparación timing-safe del OTP recibido contra el almacenado. {@code null}s rechazan
     * silenciosamente sin lanzar (se traduce arriba a {@code MFA_INVALID_CODE} /
     * {@code MFA_CODE_EXPIRED} según corresponda).
     */
    public boolean matches(String provided, String stored) {
        if (provided == null || stored == null) {
            return false;
        }
        byte[] providedBytes = provided.getBytes(StandardCharsets.UTF_8);
        byte[] storedBytes = stored.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(providedBytes, storedBytes);
    }
}
