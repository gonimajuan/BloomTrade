package co.edu.unbosque.bloomtrade.auth.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Gestiona las claves Redis que viven mientras un usuario está entre {@code /login} y
 * {@code /mfa/verify} (spec HU-F02 §5.1 paso 12, decisión D11 del plan).
 *
 * <p>Cuatro claves por sesión temporal, todas con el mismo TTL de 5 min:
 *
 * <ul>
 *   <li>{@code temp-session:{id}} — JSON de {@link TempSessionData}
 *   <li>{@code otp:{id}} — código OTP (puede sobrescribirse en {@code /mfa/resend})
 *   <li>{@code mfa:attempts:{id}} — contador de intentos de verificación
 *   <li>{@code mfa:resends:{id}} — contador de reenvíos solicitados
 * </ul>
 *
 * <p>El {@code tempSessionId} es opaco (UUID v4); no contiene información del usuario para que el
 * frontend no pueda inferir nada del valor.
 */
@Component
public class TempSessionManager {

    static final String SESSION_PREFIX = "temp-session:";
    static final String OTP_PREFIX = "otp:";
    static final String ATTEMPTS_PREFIX = "mfa:attempts:";
    static final String RESENDS_PREFIX = "mfa:resends:";
    static final Duration SESSION_TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public TempSessionManager(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * Crea una sesión temporal nueva con su OTP y los contadores en cero. Retorna el
     * {@code tempSessionId} opaco que el cliente recibirá en la respuesta del login.
     */
    public String createSession(TempSessionData data, String otp) {
        String tempSessionId = UUID.randomUUID().toString();
        String sessionJson;
        try {
            sessionJson = objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar TempSessionData", e);
        }
        redis.opsForValue().set(SESSION_PREFIX + tempSessionId, sessionJson, SESSION_TTL);
        redis.opsForValue().set(OTP_PREFIX + tempSessionId, otp, SESSION_TTL);
        redis.opsForValue().set(ATTEMPTS_PREFIX + tempSessionId, "0", SESSION_TTL);
        redis.opsForValue().set(RESENDS_PREFIX + tempSessionId, "0", SESSION_TTL);
        return tempSessionId;
    }

    /** Carga la sesión temporal si existe (spec §5.1 paso 22, Lote D). */
    public Optional<TempSessionData> getSession(String tempSessionId) {
        String json = redis.opsForValue().get(SESSION_PREFIX + tempSessionId);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, TempSessionData.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo deserializar TempSessionData", e);
        }
    }

    /** OTP vigente para la sesión, si todavía no expiró (spec §5.1 paso 23). */
    public Optional<String> getOtp(String tempSessionId) {
        return Optional.ofNullable(redis.opsForValue().get(OTP_PREFIX + tempSessionId));
    }

    /**
     * Sobrescribe el OTP en un reenvío (spec §5.2.3 paso 5). Reinicia el TTL del OTP pero
     * <strong>no</strong> el de la sesión temporal — esa sigue con su reloj original.
     */
    public void replaceOtp(String tempSessionId, String otp) {
        redis.opsForValue().set(OTP_PREFIX + tempSessionId, otp, SESSION_TTL);
    }

    /**
     * Borra todas las claves asociadas al {@code tempSessionId} de un golpe (spec §5.1 paso 27,
     * §5.3.7, §5.3.10). Idempotente: si alguna clave ya no existía, no falla.
     */
    public void invalidate(String tempSessionId) {
        redis.delete(
                List.of(
                        SESSION_PREFIX + tempSessionId,
                        OTP_PREFIX + tempSessionId,
                        ATTEMPTS_PREFIX + tempSessionId,
                        RESENDS_PREFIX + tempSessionId));
    }
}
