package com.emailservice.common.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.emailservice.common.config.AppProperties;

@Configuration(proxyBeanMethods = false)
public class MigrationConfig {

	private static final Logger log = LoggerFactory.getLogger(MigrationConfig.class);

	/** Flyway connects as email_owner (spring.flyway.*) and only when this instance may run DDL (NFR-09). */
	@Bean
	FlywayMigrationStrategy flywayMigrationStrategy(AppProperties properties) {
		return flyway -> {
			if (properties.migratesOnStartup()) {
				log.info("Applying database migrations as the schema owner");
				flyway.migrate();
			}
			else {
				log.info("Skipping database migrations for APP_ENV={} APP_ROLE={}", properties.env(), properties.role());
			}
		};
	}

}
