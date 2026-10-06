package com.emailservice.common.api;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
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
			.servers(List.of(new Server().url("/")));
	}

}
