package co.edu.unbosque.bloomtrade.auth.ratelimit;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Encapsula los contadores Redis del paso 2 (spec HU-F02 §9.3, decisión D11 del plan).
 *
 * <p>Tres claves complementarias por sesión temporal:
 *
 * <ul>
 *   <li>{@code mfa:attempts:{tempSessionId}} — fallos de OTP (inicializada en 0 por
 *       {@code TempSessionManager} cuando nace la sesión; este componente solo la incrementa).
 *   <li>{@code mfa:resends:{tempSessionId}} — reenvíos solicitados (inicializada en 0 igual).
 *   <li>{@code mfa:resend-cooldown:{tempSessionId}} — marca de cooldown entre reenvíos, TTL 30s.
 * </ul>
 *
 * <p>Las dos primeras claves comparten lifecycle con la sesión temporal (TTL 5 min, borradas en
 * {@code TempSessionManager.invalidate}). La de cooldown es ortogonal y se autodestruye con su TTL.
 */
@Component
public class MfaAttemptTracker {

    static final String ATTEMPTS_PREFIX = "mfa:attempts:";
    static final String RESENDS_PREFIX = "mfa:resends:";
    static final String COOLDOWN_PREFIX = "mfa:resend-cooldown:";
    static final Duration COOLDOWN_TTL = Duration.ofSeconds(30);

    private final StringRedisTemplate redis;

    public MfaAttemptTracker(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Incrementa atómicamente el contador de fallos de OTP y devuelve el nuevo valor. */
    public int recordFailed(String tempSessionId) {
        Long newCount = redis.opsForValue().increment(ATTEMPTS_PREFIX + tempSessionId);
        return newCount != null ? newCount.intValue() : 0;
    }

    /** Incrementa atómicamente el contador de reenvíos solicitados y devuelve el nuevo valor. */
    public int recordResend(String tempSessionId) {
        Long newCount = redis.opsForValue().increment(RESENDS_PREFIX + tempSessionId);
        return newCount != null ? newCount.intValue() : 0;
    }

    /** Lectura no destructiva del contador de reenvíos (usada para el chequeo previo de máximo). */
    public int getResendCount(String tempSessionId) {
        String raw = redis.opsForValue().get(RESENDS_PREFIX + tempSessionId);
        if (raw == null) {
            return 0;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** {@code true} si todavía no han pasado los 30s del último resend (spec §5.3.9). */
    public boolean isOnCooldown(String tempSessionId) {
        return Boolean.TRUE.equals(redis.hasKey(COOLDOWN_PREFIX + tempSessionId));
    }

    /** Segundos restantes del cooldown — alimenta el header {@code Retry-After} del 429. */
    public long cooldownSecondsRemaining(String tempSessionId) {
        Long ttl = redis.getExpire(COOLDOWN_PREFIX + tempSessionId, TimeUnit.SECONDS);
        return ttl != null && ttl > 0 ? ttl : 0L;
    }

    /** Arma el cooldown de 30s después de un resend exitoso (spec §5.2.3 paso 7). */
    public void setCooldown(String tempSessionId) {
        redis.opsForValue().set(COOLDOWN_PREFIX + tempSessionId, "1", COOLDOWN_TTL);
    }
}
