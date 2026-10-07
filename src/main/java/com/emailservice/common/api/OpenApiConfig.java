package com.emailservice.common.api;

import java.util.List;

import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;

@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

	@Bean
	OpenAPI emailServiceOpenApi() {
		return new OpenAPI()
			.info(new Info().title("email-service")
				.version("v1")
				.description("Internal transactional email service. See docs/07-api-interna.md."))
			// A fixed relative server keeps the exported contract independent of host and port (AC-25.2).
			.servers(List.of(new Server().url("/")))
			.components(new Components()
				.addSecuritySchemes("apiKey",
						new SecurityScheme().type(SecurityScheme.Type.HTTP)
							.scheme("bearer")
							.description("Tenant API key: esk_{env}_{prefix}_{secret}"))
				.addSecuritySchemes("adminKey", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
					.in(SecurityScheme.In.HEADER)
					.name("X-Admin-Key")
					.description("One of ADMIN_API_KEYS; private network only")));
	}

	/** What products integrate against: contracts/email-service.openapi.json. */
	@Bean
	GroupedOpenApi publicApi() {
		return GroupedOpenApi.builder().group("public").pathsToMatch("/v1/**", "/webhooks/**").build();
	}

	/** AC-25.3: administration in its own group: contracts/email-service-admin.openapi.json. */
	@Bean
	GroupedOpenApi adminApi() {
		return GroupedOpenApi.builder().group("admin").pathsToMatch("/admin/v1/**").build();
	}

}
