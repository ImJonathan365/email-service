package com.emailservice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.emailservice.support.IntegrationTestEnvironment;
import com.emailservice.support.PostgresTestDatabase;
import com.emailservice.support.TestHttp;
import com.emailservice.templates.engine.TemplateEngine;

/** docs/05 §1: a worker instance serves no API, only Actuator for the orchestrator. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = { "app.env=local", "app.role=worker" })
class WorkerRoleStartupTests {

	@BeforeAll
	static void migrateAsTheDeployJobWould() {
		PostgresTestDatabase.migrate();
	}

	@DynamicPropertySource
	static void environment(DynamicPropertyRegistry registry) {
		IntegrationTestEnvironment.register(registry);
	}

	@LocalServerPort
	int port;

	@Autowired
	ApplicationContext context;

	@Test
	void workerExposesOnlyActuator() throws Exception {
		TestHttp http = new TestHttp(port);
		assertThat(http.get("/actuator/health/readiness").send().statusCode()).isEqualTo(200);
		assertThat(http.get("/actuator/health/liveness").send().statusCode()).isEqualTo(200);
		for (String path : new String[] { "/v1/templates", "/admin/v1/tenants", "/v3/api-docs", "/v3/api-docs/public",
				"/swagger-ui.html" }) {
			assertThat(http.get(path).send().statusCode()).as(path).isEqualTo(404);
		}
	}

	@Test
	void apiComponentsAreNotCreatedButSharedOnesAre() {
		assertThat(context.getBeanNamesForType(FilterRegistrationBean.class)).contains("requestIdFilter")
			.doesNotContain("adminAuthenticationFilter", "apiKeyAuthenticationFilter");
		assertThat(context.containsBean("templateController")).isFalse();
		assertThat(context.containsBean("tenantAdminController")).isFalse();
		assertThat(context.getBeansOfType(TemplateEngine.class)).hasSize(1);
	}

}
