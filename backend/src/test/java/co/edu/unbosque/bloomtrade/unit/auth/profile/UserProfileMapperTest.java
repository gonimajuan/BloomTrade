package co.edu.unbosque.bloomtrade.unit.auth.profile;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.unbosque.bloomtrade.auth.domain.DocumentType;
import co.edu.unbosque.bloomtrade.auth.domain.User;
import co.edu.unbosque.bloomtrade.auth.profile.dto.UserProfileResponse;
import co.edu.unbosque.bloomtrade.auth.profile.mapper.UserProfileMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * SPEC HU-F04+F20 §10.2 constraint NO-NEGOCIABLE: el JSON serializado de {@link UserProfileResponse}
 * <strong>nunca</strong> contiene {@code passwordHash} ni la huella del hash BCrypt ({@code $2a$}).
 */
class UserProfileMapperTest {

    private static final String BCRYPT_HASH = "$2a$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTU";

    private UserProfileMapper mapper;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mapper = Mappers.getMapper(UserProfileMapper.class);
        objectMapper =
                new ObjectMapper()
                        .registerModule(new JavaTimeModule())
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    void shouldMapAllVisibleFieldsFromUser() {
        User user = sampleUser();
        UserProfileResponse response = mapper.toResponse(user);

        assertThat(response.email()).isEqualTo(user.getEmail());
        assertThat(response.nombreCompleto()).isEqualTo(user.getNombreCompleto());
        assertThat(response.telefono()).isEqualTo(user.getTelefono());
        assertThat(response.rol()).isEqualTo(user.getRol());
        assertThat(response.estado()).isEqualTo(user.getEstado());
        assertThat(response.notificationChannel()).isEqualTo(user.getNotificationChannel());
        assertThat(response.tickersOfInterest()).isEqualTo(user.getTickersOfInterest());
    }

    @Test
    void shouldNotIncludePasswordHashInSerializedJson() throws Exception {
        UserProfileResponse response = mapper.toResponse(sampleUser());

        String json = objectMapper.writeValueAsString(response);

        assertThat(json)
                .as("JSON expuesto al cliente no debe contener el campo passwordHash")
                .doesNotContain("passwordHash")
                .doesNotContain("password_hash")
                .as("JSON expuesto al cliente no debe contener la huella BCrypt $2a$")
                .doesNotContain("$2a$")
                .doesNotContain(BCRYPT_HASH);
    }

    @Test
    void shouldNotIncludeAceptoTerminosAtInSerializedJson() throws Exception {
        UserProfileResponse response = mapper.toResponse(sampleUser());

        String json = objectMapper.writeValueAsString(response);

        assertThat(json)
                .doesNotContain("aceptoTerminos")
                .doesNotContain("acepto_terminos");
    }

    private User sampleUser() {
        User user =
                User.register(
                        "juan@example.com",
                        BCRYPT_HASH,
                        "Juan Pérez",
                        DocumentType.CC,
                        "1234567890",
                        "+573001234567",
                        Instant.parse("2026-05-01T00:00:00Z"));
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(user, "createdAt", Instant.parse("2026-05-01T00:00:00Z"));
        ReflectionTestUtils.setField(user, "updatedAt", Instant.parse("2026-05-01T00:00:00Z"));
        return user;
    }
}
