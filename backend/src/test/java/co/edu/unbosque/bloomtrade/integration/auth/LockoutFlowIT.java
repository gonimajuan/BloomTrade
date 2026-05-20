package co.edu.unbosque.bloomtrade.integration.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.unbosque.bloomtrade.audit.AuditEvent;
import co.edu.unbosque.bloomtrade.audit.AuditEventType;
import co.edu.unbosque.bloomtrade.audit.Auditor;
import co.edu.unbosque.bloomtrade.notification.Notifier;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * IT del bloqueo automático por 3 intentos fallidos (spec HU-F02 §5.3.2 y §5.3.3 / Lote F T5.8).
 *
 * <p>Verifica que (1) tras 3 logins con password incorrecto, el 3er fallo dispara el lockout +
 * email + audit {@code ACCOUNT_LOCKED}, y (2) intentos posteriores devuelven 423 sin volver a
 * incrementar el contador.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LockoutFlowIT {

    private static final String EMAIL = "lockout.subject@example.com";
    private static final String CORRECT_PASSWORD = "SecurePass123";
    private static final String WRONG_PASSWORD = "WrongPass999";
    private static final String REGISTER_BODY =
            """
            {
              "email":"lockout.subject@example.com",
              "password":"SecurePass123",
              "nombreCompleto":"Lockout Subject",
              "tipoDocumento":"CC",
              "numeroDocumento":"1020304050",
              "telefono":"+573009876543",
              "aceptaTerminos":true
            }
            """;

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @MockBean private Auditor auditor;
    @MockBean private Notifier notifier;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.execute("TRUNCATE TABLE app.users CASCADE");
        flushRedis();
        clearInvocations(auditor, notifier);
        mockMvc.perform(
                        post("/api/v1/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(REGISTER_BODY))
                .andExpect(status().isCreated());
        clearInvocations(auditor, notifier);
    }

    @Test
    void shouldLockAccountAfterThreeFailedAttemptsAndRejectFurtherLogins() throws Exception {
        // Tres intentos fallidos consecutivos.
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(
                            post("/api/v1/auth/login")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(wrongLoginBody()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"));
        }

        // El 3er fallo debe haber disparado el email de bloqueo.
        verify(notifier, atLeastOnce()).sendAccountLockedEmail(any());

        // Audit: ACCOUNT_LOCKED con reason MAX_LOGIN_ATTEMPTS debe existir entre los emitidos.
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditor, atLeastOnce()).record(captor.capture());
        List<AuditEvent> events = captor.getAllValues();
        assertThat(events)
                .anyMatch(
                        e ->
                                e.eventType() == AuditEventType.ACCOUNT_LOCKED
                                        && "MAX_LOGIN_ATTEMPTS"
                                                .equals(e.details().get("reason")));

        // Cuarto intento → 423 ACCOUNT_LOCKED (independiente del password).
        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(correctLoginBody()))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error").value("ACCOUNT_LOCKED"));

        // El OTP no debe haberse generado en el último intento bloqueado.
        verify(notifier, never()).sendOtpEmail(any());
    }

    private String wrongLoginBody() {
        return """
               {"email":"%s","password":"%s"}
               """
                .formatted(EMAIL, WRONG_PASSWORD);
    }

    private String correctLoginBody() {
        return """
               {"email":"%s","password":"%s"}
               """
                .formatted(EMAIL, CORRECT_PASSWORD);
    }

    private void flushRedis() {
        var factory = redis.getConnectionFactory();
        if (factory != null) {
            factory.getConnection().serverCommands().flushDb();
        }
    }
}
