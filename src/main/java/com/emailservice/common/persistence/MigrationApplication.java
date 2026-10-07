package com.emailservice.common.persistence;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.context.annotation.Import;

import com.emailservice.common.config.AppProperties;

/**
 * The whole application context for APP_ROLE=migrate: configuration validation and Flyway as
 * email_owner, nothing else. No component scan, so no runtime DataSource, web layer or worker.
 * Deliberately not a @Configuration, so the main application's component scan never picks it up.
 */
@EnableConfigurationProperties(AppProperties.class)
@ImportAutoConfiguration({ PropertyPlaceholderAutoConfiguration.class, FlywayAutoConfiguration.class })
@Import(MigrationConfig.class)
public class MigrationApplication {

}
