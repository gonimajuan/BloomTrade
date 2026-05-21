package co.edu.unbosque.bloomtrade.integration.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.unbosque.bloomtrade.audit.AuditEvent;
import co.edu.unbosque.bloomtrade.audit.AuditEventType;
import co.edu.unbosque.bloomtrade.audit.Auditor;
import co.edu.unbosque.bloomtrade.notification.Notifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * IT del flujo {@code /api/v1/me} (spec HU-F04+F20 §11.1).
 *
 * <p>Postgres + Redis reales del docker-compose (perfil 'test'). Comparte fixtures de
 * {@link AuthFlowIT}: registro + login + MFA reales para obtener un access token vivo, y luego
 * ejercita GET/PATCH del perfil.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MeFlowIT {

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
    @Autowired private ObjectMapper objectMapper;
    @MockBean private Auditor auditor;
    @MockBean private Notifier notifier;

    private String accessToken;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.execute("TRUNCATE TABLE app.users CASCADE");
        flushRedis();
        clearInvocations(auditor, notifier);
        registerUser();
        accessToken = loginAndVerifyMfa();
        clearInvocations(auditor, notifier);
    }

    @Test
    void shouldReturnFullProfileOnGetMe() throws Exception {
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.nombreCompleto").value("Juan Pérez García"))
                .andExpect(jsonPath("$.notificationChannel").value("EMAIL"))
                .andExpect(jsonPath("$.tickersOfInterest").isArray())
                .andExpect(jsonPath("$.tickersOfInterest.length()").value(0))
                // SPEC §10.2 constraint NO-NEGOCIABLE
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password_hash").doesNotExist());

        // GET /me no se audita (SPEC §9.1 nota).
        verify(auditor, never()).record(any());
    }

    @Test
    void shouldPatchSingleFieldAndAuditPROFILE_UPDATED() throws Exception {
        mockMvc.perform(
                        patch("/api/v1/me")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"nombreCompleto\":\"Juan Carlos Pérez\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombreCompleto").value("Juan Carlos Pérez"));

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditor).record(captor.capture());
        AuditEvent emitted = captor.getValue();
        assertThat(emitted.eventType()).isEqualTo(AuditEventType.PROFILE_UPDATED);
        @SuppressWarnings("unchecked")
        List<String> changedFields = (List<String>) emitted.details().get("changedFields");
        assertThat(changedFields).containsExactly("nombreCompleto");
    }

    @Test
    void shouldEmitTwoEventsWhenNotificationChannelChanges() throws Exception {
        mockMvc.perform(
                        patch("/api/v1/me")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"notificationChannel\":\"WHATSAPP\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notificationChannel").value("WHATSAPP"));

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditor, atLeastOnce()).record(captor.capture());
        List<AuditEvent> events = captor.getAllValues();
        assertThat(events)
                .anyMatch(e -> e.eventType() == AuditEventType.PROFILE_UPDATED)
                .anyMatch(e -> e.eventType() == AuditEventType.NOTIFICATION_CHANNEL_CHANGED);
    }

    @Test
    void shouldRejectEmailReadOnlyPatch() throws Exception {
        mockMvc.perform(
                        patch("/api/v1/me")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"other@example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("READ_ONLY_FIELD_MODIFIED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("email"));
    }

    @Test
    void shouldRejectInvalidTicker() throws Exception {
        mockMvc.perform(
                        patch("/api/v1/me")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"tickersOfInterest\":[\"AAPL\",\"FOO\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_TICKER"));
    }

    @Test
    void shouldRejectDuplicateTickers() throws Exception {
        mockMvc.perform(
                        patch("/api/v1/me")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"tickersOfInterest\":[\"AAPL\",\"AAPL\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("DUPLICATE_TICKERS"));
    }

    @Test
    void shouldRejectInvalidNotificationChannel() throws Exception {
        mockMvc.perform(
                        patch("/api/v1/me")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"notificationChannel\":\"PUSH\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_INVALID_CHANNEL"));
    }

    @Test
    void shouldBeIdempotentWhenPatchDoesNotChangeAnything() throws Exception {
        mockMvc.perform(
                        patch("/api/v1/me")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"nombreCompleto\":\"Juan Pérez García\"}"))
                .andExpect(status().isOk());

        verify(auditor, never()).record(any());
    }

    private void registerUser() throws Exception {
        mockMvc.perform(
                        post("/api/v1/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(REGISTER_BODY))
                .andExpect(status().isCreated());
    }

    private String loginAndVerifyMfa() throws Exception {
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
                        .andReturn();
        String tempSessionId =
                objectMapper.readTree(loginResult.getResponse().getContentAsString())
                        .path("tempSessionId")
                        .asText();
        String otp = redis.opsForValue().get("otp:" + tempSessionId);
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
                        .andReturn();
        JsonNode body = objectMapper.readTree(verifyResult.getResponse().getContentAsString());
        return body.path("accessToken").asText();
    }

    private void flushRedis() {
        var factory = redis.getConnectionFactory();
        if (factory != null) {
            factory.getConnection().serverCommands().flushDb();
        }
    }
}
