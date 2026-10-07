package com.emailservice.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.emailservice.common.config.AppEnv;

/** AC-03.1: esk_{env}_{prefix}_{secret}, Base62, 8 + 43 characters, only SHA-256(secret) stored. */
class ApiKeyFormatTest {

	final SecureRandom random = new SecureRandom();

	@Test
	void generatedKeyHasTheDocumentedShapeAndRoundTrips() {
		ApiKeyFormat.Generated generated = ApiKeyFormat.generate(AppEnv.PRODUCTION, random);

		assertThat(generated.token()).matches("esk_live_[0-9A-Za-z]{8}_[0-9A-Za-z]{43}");
		assertThat(generated.keyPrefix()).matches("esk_live_[0-9A-Za-z]{8}");
		assertThat(generated.token()).startsWith(generated.keyPrefix() + "_");

		ApiKeyFormat.Parsed parsed = ApiKeyFormat.parse(generated.token()).orElseThrow();
		assertThat(parsed.keyPrefix()).isEqualTo(generated.keyPrefix());
		assertThat(ApiKeyFormat.hash(parsed.secret())).isEqualTo(generated.secretHash()).hasSize(32);
	}

	@Test
	void nonProductionKeysAreTestKeys() {
		assertThat(ApiKeyFormat.generate(AppEnv.LOCAL, random).token()).startsWith("esk_test_");
		assertThat(ApiKeyFormat.generate(AppEnv.STAGING, random).token()).startsWith("esk_test_");
	}

	@Test
	void secretsDoNotRepeat() {
		Set<String> tokens = new HashSet<>();
		for (int i = 0; i < 1000; i++) {
			tokens.add(ApiKeyFormat.generate(AppEnv.LOCAL, random).token());
		}
		assertThat(tokens).hasSize(1000);
	}

	@Test
	void malformedTokensAreRejected() {
		String secret = "a".repeat(43);
		assertThat(ApiKeyFormat.parse(null)).isEmpty();
		assertThat(ApiKeyFormat.parse("esk_live_abcdefgh_" + secret)).isPresent();
		assertThat(ApiKeyFormat.parse("esk_prod_abcdefgh_" + secret)).isEmpty();
		assertThat(ApiKeyFormat.parse("esk_live_abcdefg_" + secret)).isEmpty();
		assertThat(ApiKeyFormat.parse("esk_live_abcdefgh_" + secret + "a")).isEmpty();
		assertThat(ApiKeyFormat.parse("esk_live_abcdefgh_" + "a".repeat(42) + "-")).isEmpty();
		assertThat(ApiKeyFormat.parse(" esk_live_abcdefgh_" + secret)).isEmpty();
	}

	@Test
	void toStringNeverShowsTheSecret() {
		ApiKeyFormat.Generated generated = ApiKeyFormat.generate(AppEnv.LOCAL, random);
		assertThat(generated.toString()).doesNotContain(generated.token());
		ApiKeyFormat.Parsed parsed = ApiKeyFormat.parse(generated.token()).orElseThrow();
		assertThat(parsed.toString()).doesNotContain(parsed.secret());
	}

}
