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
import co.edu.unbosque.bloomtrade.auth.security.JwtService;
import co.edu.unbosque.bloomtrade.notification.Notifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
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
import org.springframework.test.web.servlet.MvcResult;

/**
 * IT del flujo de autenticación completo (spec HU-F02 §11 / Lote F T5.7).
 *
 * <p>Postgres + Redis reales del docker-compose (perfil 'test'). Notifier y Auditor mockeados:
 * sólo verificamos que la API los invoque; el OTP se lee directamente de Redis (más simple que
 * interceptar el dispatcher async). El access token se valida con el {@link JwtService} real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthFlowIT {

    private static final String EMAIL = "juan.perez@example.com";
    private static final String PASSWORD = "SecurePass123";
    private static final String REGISTER_BODY =
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
    @Autowired private StringRedisTemplate redis;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private Auditor auditor;
    @MockBean private Notifier notifier;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.execute("TRUNCATE TABLE app.users CASCADE");
        flushRedis();
        clearInvocations(auditor, notifier);
        registerUser();
        clearInvocations(auditor, notifier);
    }

    @Test
    void shouldCompleteLoginAndMfaFlow() throws Exception {
        // Paso 1: login con credenciales correctas → tempSessionId
        MvcResult loginResult =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"email":"%s","password":"%s"}
                                                """
                                                        .formatted(EMAIL, PASSWORD)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.tempSessionId").exists())
                        .andExpect(jsonPath("$.expiresInSeconds").value(300))
                        .andReturn();

        String tempSessionId =
                objectMapper.readTree(loginResult.getResponse().getContentAsString())
                        .path("tempSessionId")
                        .asText();

        // El OTP debe estar en Redis bajo otp:{tempSessionId}.
        String otp = redis.opsForValue().get("otp:" + tempSessionId);
        assertThat(otp).isNotNull().matches("\\d{6}");
        // El email se intentó (mockeado).
        verify(notifier, atLeastOnce()).sendOtpEmail(any());

        // Paso 2: verify con el OTP → 200 + JWT.
        MvcResult verifyResult =
                mockMvc.perform(
                                post("/api/v1/auth/mfa/verify")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"tempSessionId":"%s","code":"%s"}
                                                """
                                                        .formatted(tempSessionId, otp)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.accessToken").exists())
                        .andExpect(jsonPath("$.expiresIn").value(900))
                        .andExpect(jsonPath("$.user.email").value(EMAIL))
                        .andExpect(jsonPath("$.user.rol").value("INVESTOR"))
                        .andReturn();

        JsonNode verifyBody =
                objectMapper.readTree(verifyResult.getResponse().getContentAsString());
        String accessToken = verifyBody.path("accessToken").asText();

        // El JWT debe validar contra el JwtService real.
        Claims claims = jwtService.validate(accessToken);
        assertThat(claims.getSubject()).isNotBlank();
        assertThat(claims.get("role", String.class)).isEqualTo("INVESTOR");

        // La sesión temporal debe haberse invalidado.
        assertThat(redis.opsForValue().get("temp-session:" + tempSessionId)).isNull();
        assertThat(redis.opsForValue().get("otp:" + tempSessionId)).isNull();

        // Audit: LOGIN_ATTEMPT(ALLOWED) y MFA_VERIFIED deben haberse emitido.
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditor, atLeastOnce()).record(captor.capture());
        List<AuditEvent> events = captor.getAllValues();
        assertThat(events)
                .anyMatch(
                        e ->
                                e.eventType() == AuditEventType.LOGIN_ATTEMPT
                                        && e.result() == AuditEvent.AuditResult.ALLOWED);
        assertThat(events).anyMatch(e -> e.eventType() == AuditEventType.MFA_VERIFIED);
    }

    @Test
    void shouldReturn401WithInvalidCredentialsWhenPasswordWrong() throws Exception {
        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"email":"%s","password":"WrongPass123"}
                                        """
                                                .formatted(EMAIL)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"));

        verify(notifier, never()).sendOtpEmail(any());
    }

    @Test
    void shouldReturn401TempSessionInvalidWhenVerifyAfterFlush() throws Exception {
        mockMvc.perform(
                        post("/api/v1/auth/mfa/verify")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"tempSessionId":"00000000-0000-0000-0000-000000000000","code":"123456"}
                                        """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("TEMP_SESSION_INVALID"));
    }

    private void registerUser() throws Exception {
        mockMvc.perform(
                        post("/api/v1/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(REGISTER_BODY))
                .andExpect(status().isCreated());
    }

    private void flushRedis() {
        var factory = redis.getConnectionFactory();
        if (factory != null) {
            factory.getConnection().serverCommands().flushDb();
        }
    }
}
