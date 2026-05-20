package co.edu.unbosque.bloomtrade.auth.ratelimit;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Encapsula los contadores Redis del login (spec HU-F02 §9.3, decisión D11 del plan).
 *
 * <p>Maneja dos claves:
 *
 * <ul>
 *   <li>{@code login:attempts:{userId}} — contador de fallos consecutivos, TTL 1h desde el último
 *       intento. Se resetea en éxito.
 *   <li>{@code lockout:{userId}} — marca de bloqueo activo, TTL 15 min.
 * </ul>
 *
 * <p>Es un wrapper "tonto" del {@link StringRedisTemplate}: no decide políticas (eso vive en
 * {@code LoginService}), solo expone primitivas atómicas. Mantiene el LoginService ignorante del
 * shape exacto de las claves.
 */
@Component
public class LoginAttemptTracker {

    private static final String ATTEMPTS_PREFIX = "login:attempts:";
    private static final String LOCKOUT_PREFIX = "lockout:";
    static final Duration ATTEMPTS_TTL = Duration.ofHours(1);
    static final Duration LOCKOUT_TTL = Duration.ofMinutes(15);

    private final StringRedisTemplate redis;

    public LoginAttemptTracker(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Incrementa el contador de fallos y devuelve el nuevo valor. Setea el TTL de 1h al primer
     * fallo (cuando la clave nace de un INCR sobre cero).
     */
    public int recordFailed(UUID userId) {
        String key = ATTEMPTS_PREFIX + userId;
        Long newCount = redis.opsForValue().increment(key);
        if (newCount != null && newCount == 1L) {
            redis.expire(key, ATTEMPTS_TTL);
        }
        return newCount != null ? newCount.intValue() : 0;
    }

    /** {@code true} si la cuenta tiene bloqueo activo en Redis. */
    public boolean isLocked(UUID userId) {
        return Boolean.TRUE.equals(redis.hasKey(LOCKOUT_PREFIX + userId));
    }

    /**
     * Segundos restantes del bloqueo (0 si no hay bloqueo o si la clave existe sin TTL). Útil para
     * el mensaje "Intenta de nuevo en X minutos" de la respuesta 423.
     */
    public long lockoutSecondsRemaining(UUID userId) {
        Long ttl = redis.getExpire(LOCKOUT_PREFIX + userId, TimeUnit.SECONDS);
        return ttl != null && ttl > 0 ? ttl : 0L;
    }

    /** Setea {@code lockout:{userId}} con TTL de 15 min (TAC-S3 — Revocar acceso). */
    public void lock(UUID userId) {
        redis.opsForValue().set(LOCKOUT_PREFIX + userId, "1", LOCKOUT_TTL);
    }

    /** Borra el contador de intentos tras un login exitoso (spec §5.1 paso 13). */
    public void reset(UUID userId) {
        redis.delete(ATTEMPTS_PREFIX + userId);
    }
}
