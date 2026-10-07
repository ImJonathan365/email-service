package com.emailservice.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import com.emailservice.support.IntegrationTest;

/** NFR-17 and docs/07 §1/§11: uniform problem+json errors and X-Request-Id on every response. */
class ProblemResponsesTest extends IntegrationTest {

	@Test
	void unknownRouteIsAProblemWithCatalogCode() throws Exception {
		HttpResponse<String> response = http().get("/does-not-exist").send();

		assertThat(response.statusCode()).isEqualTo(404);
		assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
				type -> assertThat(type).startsWith(Problem.MEDIA_TYPE));
		String requestId = response.headers().firstValue(RequestIdFilter.HEADER).orElseThrow();
		assertThat(response.body()).contains("\"code\":\"RESOURCE_NOT_FOUND\"")
			.contains("\"status\":404")
			.contains("\"type\":\"https://email-service.internal/problems/resource-not-found\"")
			.contains("\"requestId\":\"" + requestId + "\"")
			.contains("\"instance\":\"/does-not-exist\"");
	}

	@Test
	void callerRequestIdIsEchoedWhenSafe() throws Exception {
		HttpResponse<String> response = http().get("/actuator/health")
			.header(RequestIdFilter.HEADER, "abc-123.DEF_4")
			.send();
		assertThat(response.headers().firstValue(RequestIdFilter.HEADER)).hasValue("abc-123.DEF_4");
	}

	@Test
	void unsafeRequestIdIsReplaced() throws Exception {
		HttpResponse<String> response = http().get("/actuator/health")
			.header(RequestIdFilter.HEADER, "x".repeat(65))
			.send();
		assertThat(response.headers().firstValue(RequestIdFilter.HEADER)).hasValueSatisfying(
				id -> assertThat(id).hasSize(36));
	}

}
