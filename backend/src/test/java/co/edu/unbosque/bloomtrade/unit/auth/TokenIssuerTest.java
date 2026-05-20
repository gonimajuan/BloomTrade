package co.edu.unbosque.bloomtrade.unit.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.unbosque.bloomtrade.auth.security.JwtService;
import co.edu.unbosque.bloomtrade.auth.security.TokenIssuer;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Tests unitarios del {@link TokenIssuer} (HU-F02 Lote F / T5.6). */
@ExtendWith(MockitoExtension.class)
class TokenIssuerTest {

    @Mock private JwtService jwtService;
    @InjectMocks private TokenIssuer tokenIssuer;

    @Test
    void shouldDelegateToJwtServiceAndPackageTtl() {
        UUID userId = UUID.randomUUID();
        when(jwtService.generateAccessToken(userId, "INVESTOR")).thenReturn("jwt.signed.token");
        when(jwtService.accessTokenTtl()).thenReturn(Duration.ofMinutes(15));

        TokenIssuer.IssuedAccessToken issued = tokenIssuer.issueAccessToken(userId, "INVESTOR");

        assertThat(issued.accessToken()).isEqualTo("jwt.signed.token");
        assertThat(issued.expiresInSeconds()).isEqualTo(900);
        verify(jwtService).generateAccessToken(userId, "INVESTOR");
    }
}
