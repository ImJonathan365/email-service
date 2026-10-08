package com.emailservice.common.config;

import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * A worker instance serves no API, so it publishes no API documentation either: only Actuator
 * answers on its port. Registered in META-INF/spring.factories.
 */
public class WorkerRoleEnvironment implements EnvironmentPostProcessor {

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		if (AppRole.parse(environment.getProperty("app.role", "all")) == AppRole.WORKER) {
			environment.getPropertySources()
				.addFirst(new MapPropertySource("workerRole",
						Map.of("springdoc.api-docs.enabled", "false", "springdoc.swagger-ui.enabled", "false")));
		}
	}

}
