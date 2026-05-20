package co.edu.unbosque.bloomtrade.unit.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.unbosque.bloomtrade.auth.exception.TokenExpiredException;
import co.edu.unbosque.bloomtrade.auth.exception.TokenInvalidException;
import co.edu.unbosque.bloomtrade.auth.security.JwtService;
import io.jsonwebtoken.Claims;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Tests unitarios del {@link JwtService} (HU-F02 Lote F / T5.1).
 *
 * <p>Cubre: emisión correcta, validación válida, secret &lt; 32 bytes rechazado en construcción,
 * token expirado (TTL negativo), firma alterada (secret distinto), formato malformado.
 */
class JwtServiceTest {

    private static final String SECRET =
            "test-only-jwt-secret-64-bytes-long-for-hmac-sha256-do-not-use-in-prod";
    private static final String OTHER_SECRET =
            "another-test-secret-64-bytes-long-for-hmac-sha256-do-not-use-in-prod";

    private final JwtService service = new JwtService(SECRET, 15);
    private final UUID userId = UUID.randomUUID();

    @Test
    void shouldEmitTokenWithSubjectRoleAndJti() {
        String token = service.generateAccessToken(userId, "INVESTOR");

        Claims claims = service.validate(token);

        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get("role", String.class)).isEqualTo("INVESTOR");
        assertThat(claims.getId()).isNotBlank();
        assertThat(claims.getExpiration()).isAfter(new java.util.Date());
    }

    @Test
    void shouldExposeAccessTokenTtl() {
        assertThat(service.accessTokenTtl().toMinutes()).isEqualTo(15);
    }

    @Test
    void shouldExtractJti() {
        String token = service.generateAccessToken(userId, "INVESTOR");

        String jti = service.extractJti(token);

        assertThat(jti).isNotBlank();
    }

    @Test
    void shouldRejectSecretShorterThan32Bytes() {
        assertThatThrownBy(() -> new JwtService("too-short", 15))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void shouldThrowTokenExpiredWhenTtlIsNegative() {
        JwtService expired = new JwtService(SECRET, -1);
        String token = expired.generateAccessToken(userId, "INVESTOR");

        assertThatThrownBy(() -> expired.validate(token))
                .isInstanceOf(TokenExpiredException.class);
    }

    @Test
    void shouldThrowTokenInvalidWhenSignatureIsAltered() {
        String token = service.generateAccessToken(userId, "INVESTOR");
        JwtService otherSecret = new JwtService(OTHER_SECRET, 15);

        assertThatThrownBy(() -> otherSecret.validate(token))
                .isInstanceOf(TokenInvalidException.class);
    }

    @Test
    void shouldThrowTokenInvalidWhenTokenIsMalformed() {
        assertThatThrownBy(() -> service.validate("not.a.jwt"))
                .isInstanceOf(TokenInvalidException.class);
    }
}
