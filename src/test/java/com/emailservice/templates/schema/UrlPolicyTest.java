package com.emailservice.templates.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.emailservice.common.api.Problem.FieldError;
import com.emailservice.common.config.AppEnv;

import tools.jackson.databind.json.JsonMapper;

/**
 * AC-07.8: URL variables must be https on an allowed host or one of its subdomains. ADR-0021: with
 * APP_ENV=local only, links to localhost and *.localhost are accepted too.
 */
class UrlPolicyTest {

	static final List<String> HOSTS = List.of("colmena.cr", "App.Example.test");

	static final List<String> LOCAL_LINKS = List.of("http://localhost:3000/reset?t=1", "https://localhost/x",
			"http://colmena.localhost:5173/verify", "HTTP://App.Colmena.LOCALHOST/path", "http://localhost");

	static final List<String> NEVER = List.of("http://colmena.cr/reset", "https://colmena.cr.evil.test/",
			"https://evilcolmena.cr/", "https://example.test/", "javascript:alert(1)", "https://colmena.cr@evil.test/",
			"https://user@colmena.cr/", "//colmena.cr/x", "not a url", "https:///nohost", "http://localhost.evil.test/",
			"http://evillocalhost/", "http://user@localhost/", "ftp://localhost/file", "http://127.0.0.1/",
			"javascript://localhost/%0aalert(1)", "http://[::1]/");

	@ParameterizedTest
	@ValueSource(strings = { "https://colmena.cr/reset?t=1", "https://app.colmena.cr/x", "https://a.b.colmena.cr",
			"HTTPS://app.example.test/path", "https://cdn.app.example.test/logo.png" })
	void allowsHttpsOnAllowedHostsAndSubdomains(String url) {
		for (AppEnv env : AppEnv.values()) {
			assertThat(UrlPolicy.allowed(url, HOSTS, env)).as(env.name()).isTrue();
		}
	}

	@ParameterizedTest
	@EnumSource(AppEnv.class)
	void rejectsEverythingElseInEveryEnvironment(AppEnv env) {
		for (String url : NEVER) {
			assertThat(UrlPolicy.allowed(url, HOSTS, env)).as(env + " " + url).isFalse();
		}
	}

	@Test
	void adr_0021_localhostLinksOnlyWithAppEnvLocal() {
		for (String url : LOCAL_LINKS) {
			assertThat(UrlPolicy.allowed(url, HOSTS, AppEnv.LOCAL)).as(url).isTrue();
			assertThat(UrlPolicy.allowed(url, List.of(), AppEnv.LOCAL)).as("needs no allowedLinkHosts: " + url).isTrue();
			assertThat(UrlPolicy.allowed(url, HOSTS, AppEnv.STAGING)).as(url).isFalse();
			assertThat(UrlPolicy.allowed(url, HOSTS, AppEnv.PRODUCTION)).as(url).isFalse();
		}
	}

	@Test
	void adr_0021_localDoesNotRelaxAnyOtherHost() {
		assertThat(UrlPolicy.allowed("http://colmena.cr/reset", HOSTS, AppEnv.LOCAL)).isFalse();
		assertThat(UrlPolicy.allowed("http://localhost/", List.of("localhost"), AppEnv.STAGING))
			.as("not even if a tenant lists it").isFalse();
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
				"""), HOSTS, AppEnv.PRODUCTION);
		assertThat(errors).extracting(FieldError::field)
			.containsExactlyInAnyOrder("variables.links[1]", "variables.order.trackingUrl");
	}

}
