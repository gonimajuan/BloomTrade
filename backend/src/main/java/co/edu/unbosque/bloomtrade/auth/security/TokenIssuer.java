package co.edu.unbosque.bloomtrade.auth.security;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Punto único de emisión de tokens para HU-F02 (decisión D18: solo access token; refresh + cookie
 * se difieren a la mini-HU {@code HU-F0X-token-rotation-logout}).
 *
 * <p>Encapsula la llamada a {@link JwtService} y empaqueta el resultado con la duración configurada
 * para que {@code MfaService} pueda devolver {@code expiresIn} en su respuesta sin reabrir el
 * {@code JwtService} fuera del módulo de seguridad.
 */
@Component
public class TokenIssuer {

    private final JwtService jwtService;

    public TokenIssuer(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    /** Emite un access token JWT y devuelve el par {@code (token, expiresInSeconds)}. */
    public IssuedAccessToken issueAccessToken(UUID userId, String role) {
        String accessToken = jwtService.generateAccessToken(userId, role);
        long ttlSeconds = jwtService.accessTokenTtl().toSeconds();
        return new IssuedAccessToken(accessToken, Math.toIntExact(ttlSeconds));
    }

    /** Resultado de la emisión: el JWT firmado y los segundos hasta su expiración. */
    public record IssuedAccessToken(String accessToken, int expiresInSeconds) {}
}
