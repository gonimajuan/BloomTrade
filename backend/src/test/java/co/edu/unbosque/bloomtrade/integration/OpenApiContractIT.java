package co.edu.unbosque.bloomtrade.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Test de contrato OpenAPI (CONVENTIONS §7.2, spec HU-F01 §15 DoD): el spec generado por
 * SpringDoc en {@code /v3/api-docs} contiene {@code POST /api/v1/auth/register} con los códigos
 * 201/400/409/500 documentados.
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
        })
@ActiveProfiles("test")
class OpenApiContractIT {

    @Autowired private TestRestTemplate rest;

    @Test
    void shouldExposeRegisterEndpointInOpenApiWithAllResponseCodes() throws Exception {
        String body = rest.getForObject("/v3/api-docs", String.class);
        assertThat(body).isNotBlank();

        JsonNode root = new ObjectMapper().readTree(body);
        JsonNode post =
                root.path("paths").path("/api/v1/auth/register").path("post");
        assertThat(post.isMissingNode()).as("POST /api/v1/auth/register debe existir").isFalse();

        JsonNode responses = post.path("responses");
        assertThat(responses.has("201")).as("201 documentado").isTrue();
        assertThat(responses.has("400")).as("400 documentado").isTrue();
        assertThat(responses.has("409")).as("409 documentado").isTrue();
        assertThat(responses.has("500")).as("500 documentado").isTrue();
    }
}
