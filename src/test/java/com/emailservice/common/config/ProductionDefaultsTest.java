package com.emailservice.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

/**
 * The values application.yaml gives when no variable is set: what production gets unless it sets
 * one. Test overrides (IntegrationTestEnvironment) must never be what ships.
 */
class ProductionDefaultsTest {

	static PropertySourcesPropertyResolver productionDefaults() throws Exception {
		MutablePropertySources sources = new MutablePropertySources();
		new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))
			.forEach(sources::addLast);
		return new PropertySourcesPropertyResolver(sources);
	}

	@Test
	void docs08_4_theAuthFailureLimitPerIpIsThirty() throws Exception {
		assertThat(productionDefaults().getProperty("app.http.auth-failures-per-minute-per-ip", Integer.class))
			.isEqualTo(30);
		assertThat(new AppProperties.Http(List.of(), 0).authFailuresPerMinutePerIp())
			.as("an unset value binds to the same default")
			.isEqualTo(30);
	}

	@Test
	void docs05_6_maintenanceAndPoolDefaults() throws Exception {
		PropertySourcesPropertyResolver defaults = productionDefaults();
		Map.of("app.worker.variables-retention-days", 7, "app.worker.idempotency-retention-hours", 24,
				"app.worker.sent-final-after-hours", 72, "app.worker.queue-age-alert-seconds", 300)
			.forEach((name, value) -> assertThat(defaults.getProperty(name, Integer.class)).as(name).isEqualTo(value));
		// The pool sizes are not configured for production at all: DataSourceConfig's 10 and 4 apply.
		assertThat(defaults.getProperty("app.db.tenant-pool-size")).isNull();
		assertThat(defaults.getProperty("app.db.system-pool-size")).isNull();
	}

}
