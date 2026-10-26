package com.grallum.application.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/** Documents explicit management Bearer credentials; Docker uses its separate token audience. */
@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(
    info =
        @Info(
            title = "Grallum Registry Management",
            version = "0.2.0",
            description =
                "Exchange JSON credentials at /api/auth/token. Browser forms use CSRF-protected JWT"
                    + " cookies."),
    security = @SecurityRequirement(name = "managementJwt"))
@SecurityScheme(
    name = "managementJwt",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT")
public class OpenApiConfig {}
