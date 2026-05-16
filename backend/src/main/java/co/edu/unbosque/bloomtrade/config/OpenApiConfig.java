package co.edu.unbosque.bloomtrade.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI bloomtradeOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("BloomTrade API")
                        .version("0.1.0-SNAPSHOT")
                        .description("API de la plataforma de Day Trading BloomTrade. "
                                + "Proyecto académico — Universidad El Bosque, Ing. de Software 2, 2026.")
                        .contact(new Contact().name("BloomTrade Team")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .name(BEARER_SCHEME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
