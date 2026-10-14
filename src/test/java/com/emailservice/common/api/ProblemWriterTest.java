package com.emailservice.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import tools.jackson.databind.json.JsonMapper;

/** Filters answer through ProblemWriter: a 429 written there carries Retry-After too (AC-21.1, docs/08 §4). */
class ProblemWriterTest {

	@Test
	void aRateLimitedProblemCarriesRetryAfterInWholeSeconds() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();

		new ProblemWriter(JsonMapper.builder().build()).write(new MockHttpServletRequest("GET", "/v1/emails"), response,
				ApiException.rateLimited("Too many failed authentications.", Duration.ofMillis(12_300)));

		assertThat(response.getStatus()).isEqualTo(429);
		assertThat(response.getHeader("Retry-After")).isEqualTo("13");
		assertThat(response.getContentAsString()).contains("RATE_LIMITED");
	}

	@Test
	void otherProblemsHaveNoRetryAfter() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();

		new ProblemWriter(JsonMapper.builder().build()).write(new MockHttpServletRequest("GET", "/v1/emails"), response,
				new ApiException(ErrorCode.UNAUTHENTICATED, "A valid API key is required."));

		assertThat(response.getHeader("Retry-After")).isNull();
	}

}
