package com.emailservice.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.emailservice.support.IntegrationTest;

/** FR-24 (AC-24.1) and FR-25 (AC-25.1) against a real PostgreSQL with production roles. */
class OperationalEndpointsTest extends IntegrationTest {

	static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

	@LocalServerPort
	int port;

	@Autowired
	HealthEndpointGroups healthGroups;

	@Test
	void readinessIsUpAgainstTheDatabase() throws Exception {
		HttpResponse<String> response = get("/actuator/health/readiness");
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"status\":\"UP\"");
	}

	@Test
	void livenessIsUp() throws Exception {
		HttpResponse<String> response = get("/actuator/health/liveness");
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"status\":\"UP\"");
	}

	@Test
	void livenessHasNoExternalDependenciesAndReadinessOnlyTheDatabase() {
		assertThat(healthGroups.get("liveness").isMember("db")).isFalse();
		assertThat(healthGroups.get("liveness").isMember("livenessState")).isTrue();
		assertThat(healthGroups.get("readiness").isMember("db")).isTrue();
		assertThat(healthGroups.get("readiness").isMember("diskSpace")).isFalse();
	}

	@Test
	void onlyHealthInfoMetricsAndPrometheusAreExposed() throws Exception {
		for (String exposed : new String[] { "/actuator/health", "/actuator/info", "/actuator/metrics",
				"/actuator/prometheus" }) {
			assertThat(get(exposed).statusCode()).as(exposed).isEqualTo(200);
		}
		for (String hidden : new String[] { "/actuator/env", "/actuator/beans", "/actuator/configprops",
				"/actuator/loggers", "/actuator/heapdump", "/actuator/threaddump", "/actuator/flyway" }) {
			assertThat(get(hidden).statusCode()).as(hidden).isEqualTo(404);
		}
	}

	@Test
	void swaggerUiAndApiDocsAreServed() throws Exception {
		HttpResponse<String> ui = get("/swagger-ui.html");
		assertThat(ui.statusCode()).isEqualTo(200);
		assertThat(ui.body()).contains("swagger-ui");

		HttpResponse<String> docs = get("/v3/api-docs");
		assertThat(docs.statusCode()).isEqualTo(200);
		assertThat(docs.body()).contains("\"openapi\"");
	}

	private HttpResponse<String> get(String path) throws IOException, InterruptedException {
		return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
				HttpResponse.BodyHandlers.ofString());
	}

}
