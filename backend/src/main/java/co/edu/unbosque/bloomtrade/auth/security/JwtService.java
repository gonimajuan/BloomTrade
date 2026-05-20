package co.edu.unbosque.bloomtrade.auth.security;

import co.edu.unbosque.bloomtrade.auth.exception.TokenExpiredException;
import co.edu.unbosque.bloomtrade.auth.exception.TokenInvalidException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Emisión y validación de JWT access tokens (HS256, jjwt 0.12.x).
 *
 * <p>Secret tomado de la env var {@code JWT_SECRET} o de {@code jwt.secret} en YAML. Mínimo 32
 * bytes (256 bits) — se falla el arranque si es menor. TTL del access token:
 * {@code JWT_ACCESS_TTL_MINUTES} o {@code jwt.access-ttl-minutes} (default 15).
 *
 * <p>Claims: {@code sub=userId}, {@code role=<role>}, {@code jti=UUID}. El {@code jti} habilita
 * la blacklist post-logout que vendrá en la mini-HU futura (D18 del plan difiere refresh+logout).
 *
 * <p>Refresh token NO es JWT — es un string aleatorio gestionado por {@code TokenIssuer}
 * (también diferido por D18). Este service solo maneja el access.
 */
@Service
public class JwtService {

    private final SecretKey signingKey;
    private final Duration accessTokenTtl;

    public JwtService(
            @Value("${jwt.secret:${JWT_SECRET:}}") String secret,
            @Value("${jwt.access-ttl-minutes:${JWT_ACCESS_TTL_MINUTES:15}}")
                    long accessTtlMinutes) {
        // Default vacío en el placeholder interno evita el "Could not resolve placeholder
        // JWT_SECRET" cuando ni la env var ni jwt.secret están definidos en algún perfil.
        // Si llegamos acá con un secret vacío, fallamos con un mensaje accionable.
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET debe tener al menos 32 bytes (256 bits). "
                            + "Generar con: openssl rand -base64 64");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.accessTokenTtl = Duration.ofMinutes(accessTtlMinutes);
    }

    /** Emite un access token firmado HS256 con jti aleatorio. */
    public String generateAccessToken(UUID userId, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("role", role)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenTtl)))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Valida firma + expiración y retorna los claims.
     *
     * @throws TokenExpiredException si el {@code exp} ya pasó
     * @throws TokenInvalidException si la firma falla, el formato es inválido, o cualquier otro
     *     problema estructural
     */
    public Claims validate(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw new TokenExpiredException("Access token expirado", e);
        } catch (JwtException | IllegalArgumentException e) {
            throw new TokenInvalidException("Access token inválido", e);
        }
    }

    /** Extrae el {@code jti} (token id). Atajo para flujos que ya validaron el token. */
    public String extractJti(String token) {
        return validate(token).getId();
    }

    /** Duración configurada del access token; útil para devolver {@code expiresIn} en /mfa/verify. */
    public Duration accessTokenTtl() {
        return accessTokenTtl;
    }
}
