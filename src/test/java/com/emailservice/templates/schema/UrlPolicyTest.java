package com.emailservice.templates.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.emailservice.common.api.Problem.FieldError;

import tools.jackson.databind.json.JsonMapper;

/** AC-07.8: URL variables must be https on an allowed host or one of its subdomains. */
class UrlPolicyTest {

	static final List<String> HOSTS = List.of("colmena.cr", "App.Example.test");

	@ParameterizedTest
	@ValueSource(strings = { "https://colmena.cr/reset?t=1", "https://app.colmena.cr/x", "https://a.b.colmena.cr",
			"HTTPS://app.example.test/path", "https://cdn.app.example.test/logo.png" })
	void allowsHttpsOnAllowedHostsAndSubdomains(String url) {
		assertThat(UrlPolicy.allowed(url, HOSTS)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "http://colmena.cr/reset", "https://colmena.cr.evil.test/", "https://evilcolmena.cr/",
			"https://example.test/", "javascript:alert(1)", "https://colmena.cr@evil.test/", "https://user@colmena.cr/",
			"//colmena.cr/x", "not a url", "https:///nohost" })
	void rejectsEverythingElse(String url) {
		assertThat(UrlPolicy.allowed(url, HOSTS)).isFalse();
	}

	@Test
	void checksEveryUriVariableIncludingNestedOnes() {
		JsonMapper json = JsonMapper.builder().build();
		VariablesSchema schema = VariablesSchema.parse(json.readTree("""
				{"properties": {"resetUrl": {"type": "string", "format": "uri"}, "name": {"type": "string"},
				 "links": {"type": "array", "items": {"type": "string", "format": "uri"}},
				 "order": {"type": "object", "properties": {"trackingUrl": {"type": "string", "format": "uri"}}}}}
				"""), "variablesSchema");
		List<FieldError> errors = UrlPolicy.check(schema, json.readTree("""
				{"resetUrl": "https://colmena.cr/r", "name": "http://not-checked.test",
				 "links": ["https://colmena.cr/a", "https://evil.test/b"],
				 "order": {"trackingUrl": "http://colmena.cr/t"}}
				"""), HOSTS);
		assertThat(errors).extracting(FieldError::field)
			.containsExactlyInAnyOrder("variables.links[1]", "variables.order.trackingUrl");
	}

}
