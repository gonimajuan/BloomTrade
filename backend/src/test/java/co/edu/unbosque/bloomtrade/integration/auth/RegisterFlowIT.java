package co.edu.unbosque.bloomtrade.integration.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.unbosque.bloomtrade.audit.AuditEvent;
import co.edu.unbosque.bloomtrade.audit.AuditEventType;
import co.edu.unbosque.bloomtrade.audit.Auditor;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * IT del flujo de registro (spec HU-F01 §11). Postgres + Redis reales del docker-compose
 * (perfil 'test'). La exclusión inicial de RedisAutoConfiguration (D12 de HU-F01) se removió en
 * Lote F de HU-F02 porque los beans nuevos del módulo auth (LoginAttemptTracker,
 * TempSessionManager, MfaAttemptTracker) requieren {@code StringRedisTemplate}; sin él el context
 * no levanta. JavaMailSender mockeado (no SMTP real); Auditor mockeado para verificar emisión.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegisterFlowIT {

    private static final String URL = "/api/v1/auth/register";
    private static final String VALID_BODY =
            """
            {
              "email":"juan.perez@example.com",
              "password":"SecurePass123",
              "nombreCompleto":"Juan Pérez García",
              "tipoDocumento":"CC",
              "numeroDocumento":"1234567890",
              "telefono":"+573001234567",
              "aceptaTerminos":true
            }
            """;

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private Auditor auditor;
    @MockBean private JavaMailSender mailSender;

    @BeforeEach
    void setUp() {
        // ON DELETE CASCADE en user_balances → un TRUNCATE de users limpia ambas tablas.
        jdbc.execute("TRUNCATE TABLE app.users CASCADE");
        clearInvocations(auditor, mailSender);
        // Evita NPE si el dispatcher async corre durante el test.
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
    }

    @Test
    void shouldCreateUserAndBalanceAndAuditUserRegistered() throws Exception {
        MvcResult res =
                mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.email").value("juan.perez@example.com"))
                        .andExpect(jsonPath("$.rol").value("INVESTOR"))
                        .andExpect(jsonPath("$.estado").value("ACTIVE"))
                        .andExpect(jsonPath("$.id").exists())
                        .andReturn();

        String hash =
                jdbc.queryForObject(
                        "SELECT password_hash FROM app.users WHERE email=?",
                        String.class,
                        "juan.perez@example.com");
        assertThat(hash).startsWith("$2a$12$").hasSize(60);

        BigDecimal balance =
                jdbc.queryForObject(
                        "SELECT balance FROM app.user_balances", BigDecimal.class);
        assertThat(balance).isEqualByComparingTo("10000.00");

        // Listener post-commit emite USER_REGISTERED sincrónicamente al cerrar la tx.
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditor, atLeastOnce()).record(captor.capture());
        List<AuditEvent> events = captor.getAllValues();
        assertThat(events)
                .anyMatch(e -> e.eventType() == AuditEventType.USER_REGISTERED
                        && "INVESTOR".equals(e.actorRole()));
        assertThat(res.getResponse().getHeader("X-Trace-Id")).isNotBlank();
    }

    @Test
    void shouldReject409WhenEmailAlreadyRegisteredIgnoringCase() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated());
        clearInvocations(auditor);

        String upperCaseBody =
                VALID_BODY.replace("juan.perez@example.com", "JUAN.PEREZ@EXAMPLE.COM");
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(upperCaseBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("EMAIL_ALREADY_REGISTERED"))
                .andExpect(jsonPath("$.traceId").exists());

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditor, atLeastOnce()).record(captor.capture());
        assertThat(captor.getAllValues())
                .anyMatch(e -> e.eventType() == AuditEventType.USER_REGISTRATION_FAILED
                        && "EMAIL_DUPLICATE".equals(e.details().get("reason")));

        Long count = jdbc.queryForObject("SELECT count(*) FROM app.users", Long.class);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void shouldReject400WithTopLevelCodeWhenTermsNotAccepted() throws Exception {
        String body = VALID_BODY.replace("\"aceptaTerminos\":true", "\"aceptaTerminos\":false");

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                // D14: un solo fieldError → su código sube al 'error' de primer nivel.
                .andExpect(jsonPath("$.error").value("TERMS_NOT_ACCEPTED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("aceptaTerminos"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("TERMS_NOT_ACCEPTED"));

        verify(auditor, never()).record(any());
        Long count = jdbc.queryForObject("SELECT count(*) FROM app.users", Long.class);
        assertThat(count).isZero();
    }

    @Test
    void shouldReject400WithWeakPasswordCode() throws Exception {
        String body = VALID_BODY.replace("\"SecurePass123\"", "\"Short1\"");

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("WEAK_PASSWORD"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("password"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("WEAK_PASSWORD"));
    }
}
