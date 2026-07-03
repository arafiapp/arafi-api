package com.yourara.arafi.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

        public static final String USER_JWT_SCHEME = "userJwtAuth";
        public static final String API_KEY_SCHEME = "apiKeyAuth";

        @Bean
        public OpenAPI arafiOpenAPI() {
                return new OpenAPI()
                                .info(apiInfo())
                                .servers(List.of(
                                                new Server()
                                                                .url("http://localhost:8080")
                                                                .description("Local Development Server (arafi-api)"),
                                                new Server()
                                                                .url("https://arafi-api.onrender.com")
                                                                .description("Production Arafi API (live)")))
                                .components(new Components()
                                                .addSecuritySchemes(USER_JWT_SCHEME, userJwtScheme())
                                                .addSecuritySchemes(API_KEY_SCHEME, apiKeyScheme()));
        }

        private Info apiInfo() {
                return new Info()
                                .title("Arafi Financial Layer API Engine")
                                .description("""
                                                ## Arafi — Financial Layer for the Ara Platform

                                                Arafi handles workspace creations, developer API keys, virtual accounts provisioning, and ledgers/balances.

                                                ### Authentication Routes

                                                1. **User Dashboard Context (JWT)**:
                                                   - Required for `/v1/workspaces/**` endpoints.
                                                   - Obtain via `POST /v1/auth/login`.
                                                   - Authorize in Swagger using `userJwtAuth` (Bearer token).

                                                2. **Developer API Context (API Keys)**:
                                                   - Required for `/v1/virtual-accounts/**` and `/v1/balances/**`.
                                                   - Generated when creating a workspace. Prefix is `arafi_test_` or `arafi_live_`.
                                                   - Authorize in Swagger using `apiKeyAuth` (Bearer token).

                                                ### Response Envelope
                                                Standard HTTP status codes are returned. Payload structures vary based on endpoints.
                                                """)
                                .version("v1.0.0")
                                .contact(new Contact()
                                                .name("Ara Finance & Engineering")
                                                .email("welcome@yourara.com"))
                                .license(new License()
                                                .name("Proprietary")
                                                .url("https://arafi-platform.vercel.app/"));
        }

        private SecurityScheme userJwtScheme() {
                return new SecurityScheme()
                                .name(USER_JWT_SCHEME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Paste your user JWT access token here (without the 'Bearer ' prefix).");
        }

        private SecurityScheme apiKeyScheme() {
                return new SecurityScheme()
                                .name(API_KEY_SCHEME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("opaque")
                                .description("Paste your developer API key here (without the 'Bearer ' prefix). e.g., `arafi_test_...` or `arafi_live_...`.");
        }
}
