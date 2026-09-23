package com.sahmhoot.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger 문서 설정. 화면: /swagger-ui.html */
@Configuration
public class OpenApiConfig {

  private static final String BEARER_JWT = "bearerAuth";

  @Bean
  public OpenAPI openApi() {
    return new OpenAPI()
        .info(new Info().title("SahmHoot API").version("v1"))
        .components(
            new Components()
                .addSecuritySchemes(
                    BEARER_JWT,
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
        .addSecurityItem(new SecurityRequirement().addList(BEARER_JWT));
  }
}
