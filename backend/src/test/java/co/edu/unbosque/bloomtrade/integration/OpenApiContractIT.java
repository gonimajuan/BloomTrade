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
 * Test de contrato OpenAPI (CONVENTIONS §7.2): el spec generado por SpringDoc en
 * {@code /v3/api-docs} contiene los endpoints de auth con todos los códigos documentados.
 *
 * <p>Cubre HU-F01 ({@code /register}) y HU-F02 ({@code /login}, {@code /mfa/verify},
 * {@code /mfa/resend}). Redis se autoconfigura porque los IT del Lote F lo usan y queremos un
 * único context cacheado por Spring Boot Test entre tests.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OpenApiContractIT {

    @Autowired private TestRestTemplate rest;

    @Test
    void shouldExposeRegisterEndpointInOpenApiWithAllResponseCodes() throws Exception {
        JsonNode responses = postResponses("/api/v1/auth/register");

        assertThat(responses.has("201")).as("201 documentado").isTrue();
        assertThat(responses.has("400")).as("400 documentado").isTrue();
        assertThat(responses.has("409")).as("409 documentado").isTrue();
        assertThat(responses.has("500")).as("500 documentado").isTrue();
    }

    @Test
    void shouldExposeLoginEndpointInOpenApiWithAllResponseCodes() throws Exception {
        JsonNode responses = postResponses("/api/v1/auth/login");

        assertThat(responses.has("200")).as("200 documentado").isTrue();
        assertThat(responses.has("400")).as("400 documentado").isTrue();
        assertThat(responses.has("401")).as("401 documentado").isTrue();
        assertThat(responses.has("403")).as("403 documentado").isTrue();
        assertThat(responses.has("423")).as("423 documentado").isTrue();
        assertThat(responses.has("500")).as("500 documentado").isTrue();
    }

    @Test
    void shouldExposeMfaVerifyEndpointInOpenApiWithAllResponseCodes() throws Exception {
        JsonNode responses = postResponses("/api/v1/auth/mfa/verify");

        assertThat(responses.has("200")).as("200 documentado").isTrue();
        assertThat(responses.has("400")).as("400 documentado").isTrue();
        assertThat(responses.has("401")).as("401 documentado").isTrue();
        assertThat(responses.has("403")).as("403 documentado").isTrue();
        assertThat(responses.has("500")).as("500 documentado").isTrue();
    }

    @Test
    void shouldExposeMfaResendEndpointInOpenApiWithAllResponseCodes() throws Exception {
        JsonNode responses = postResponses("/api/v1/auth/mfa/resend");

        assertThat(responses.has("200")).as("200 documentado").isTrue();
        assertThat(responses.has("400")).as("400 documentado").isTrue();
        assertThat(responses.has("401")).as("401 documentado").isTrue();
        assertThat(responses.has("429")).as("429 documentado").isTrue();
        assertThat(responses.has("500")).as("500 documentado").isTrue();
    }

    private JsonNode postResponses(String path) throws Exception {
        String body = rest.getForObject("/v3/api-docs", String.class);
        assertThat(body).isNotBlank();
        JsonNode root = new ObjectMapper().readTree(body);
        JsonNode post = root.path("paths").path(path).path("post");
        assertThat(post.isMissingNode()).as("POST %s debe existir", path).isFalse();
        return post.path("responses");
    }
}
