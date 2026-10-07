package com.emailservice.support;

import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Full application (APP_ENV=local, APP_ROLE=all) against the shared container; one cached context.
 * Metrics export is re-enabled so the Prometheus endpoint exists as it does in production.
 */
@AutoConfigureMetrics
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = { "app.env=local", "app.role=all" })
public abstract class IntegrationTest {

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		IntegrationTestDatabase.register(registry);
	}

	protected TestHttp http() {
		return new TestHttp(port);
	}

}
