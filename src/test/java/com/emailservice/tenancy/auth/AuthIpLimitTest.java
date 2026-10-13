package com.emailservice.tenancy.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.net.InetAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.emailservice.common.api.ApiException;
import com.emailservice.common.api.ErrorCode;
import com.emailservice.support.AdminApi;
import com.emailservice.support.IntegrationTest;
import com.emailservice.support.MutableClock;
import com.emailservice.support.PostgresTestDatabase;
import com.emailservice.support.PostgresTestDatabase.Role;
import com.emailservice.tenancy.AuthenticatedApiKey;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * docs/08 §4 (owner, 2026-10-12): an IP past the failure limit gets 429 only for requests without
 * a known key prefix, without touching the database; known prefixes keep the full check. Driven
 * through the real authenticator with documentation addresses, so other tests keep 127.0.0.1.
 */
class AuthIpLimitTest extends IntegrationTest {

	@Autowired
	ApiKeyAuthenticator authenticator;

	@Autowired
	AuthFailuresByIp failures;

	@Autowired
	MeterRegistry meters;

	@Autowired
	MutableClock clock;

	AdminApi admin;

	InetAddress flooding;

	InetAddress other;

	@BeforeEach
	void setUp() throws Exception {
		admin = new AdminApi(http());
		// Fresh addresses per test, and a whole minute ahead: the window cannot roll over mid-test.
		flooding = InetAddress.getByName("203.0.113." + (1 + (int) (Math.random() * 250)));
		other = InetAddress.getByName("198.51.100." + (1 + (int) (Math.random() * 250)));
		long millisIntoMinute = clock.millis() % 60_000;
		clock.advance(Duration.ofMillis(60_000 - millisIntoMinute + 1_000));
	}

	@AfterEach
	void restoreClock() {
		clock.reset();
	}

	ApiException attempt(String authorization, InetAddress ip) {
		return catchThrowableOfType(ApiException.class,
				() -> authenticator.authenticate(authorization, ip, "req-" + UUID.randomUUID()));
	}

	void floodFrom(InetAddress ip) {
		for (int i = 0; i < failures.threshold(); i++) {
			assertThat(attempt("Bearer not-a-key", ip).code()).isEqualTo(ErrorCode.UNAUTHENTICATED);
		}
	}

	double lookups() {
		return meters.counter("email.auth.key.lookups").count();
	}

	/** A well-formed key whose prefix no key has. */
	static String inventedKey() {
		return "Bearer esk_test_Zz9Yy8Xx_" + "A".repeat(43);
	}

	@Test
	void docs08_4_pastTheLimitAnUnknownPrefixGets429WithoutADatabaseLookup() {
		floodFrom(flooding);
		double before = lookups();

		for (String authorization : new String[] { null, "Bearer not-a-key", inventedKey() }) {
			ApiException limited = attempt(authorization, flooding);
			assertThat(limited.code()).isEqualTo(ErrorCode.RATE_LIMITED);
			assertThat(limited.retryAfterSeconds()).isBetween(1L, 60L);
		}

		assertThat(lookups() - before).as("no database lookup").isZero();
	}

	@Test
	void docs08_4_aValidKeyOfAnotherTenantStillWorksFromABlockedAddress() throws Exception {
		AdminApi.Key valid = admin.issueKey(admin.createTenant(), "{\"name\": \"healthy\"}");
		floodFrom(flooding);

		AuthenticatedApiKey key = authenticator.authenticate(valid.bearer(), flooding, "req-ok");

		assertThat(key.keyId()).isEqualTo(valid.id());
	}

	@Test
	void docs08_4_aKnownPrefixWithAWrongSecretIsCheckedNotLimited() throws Exception {
		AdminApi.Key valid = admin.issueKey(admin.createTenant(), "{\"name\": \"typo\"}");
		floodFrom(flooding);
		String wrongSecret = valid.bearer().substring(0, valid.bearer().length() - 5) + "AAAAA";
		double before = lookups();

		assertThat(attempt(wrongSecret, flooding).code()).isEqualTo(ErrorCode.UNAUTHENTICATED);
		assertThat(lookups() - before).isEqualTo(1.0);
	}

	@Test
	void docs08_4_aRevokedKeyIs401AtOnceEvenWhileItsPrefixIsStillKnown() throws Exception {
		AdminApi.Key key = admin.issueKey(admin.createTenant(), "{\"name\": \"soon-revoked\"}");
		assertThat(authenticator.authenticate(key.bearer(), other, "req-1").keyId()).isEqualTo(key.id());
		// Revoked by another instance: this one's prefix set still has it until its next refresh.
		try (Connection system = PostgresTestDatabase.connect(Role.SYSTEM);
				PreparedStatement revoke = system
					.prepareStatement("UPDATE api_key SET status = 'REVOKED', revoked_at = now() WHERE id = ?")) {
			revoke.setObject(1, key.id());
			revoke.executeUpdate();
		}
		floodFrom(flooding);

		assertThat(attempt(key.bearer(), other).code()).isEqualTo(ErrorCode.UNAUTHENTICATED);
		assertThat(attempt(key.bearer(), flooding).code()).as("known prefix: checked, not limited")
			.isEqualTo(ErrorCode.UNAUTHENTICATED);

		// After the next refresh the prefix is unknown, and the flooding address is limited for it.
		clock.advance(KnownKeyPrefixes.REFRESH_EVERY.plusSeconds(1));
		floodFrom(flooding);
		assertThat(attempt(key.bearer(), flooding).code()).isEqualTo(ErrorCode.RATE_LIMITED);
	}

	@Test
	void docs08_4_anotherAddressAndTheNextMinuteAreUnaffected() {
		floodFrom(flooding);

		assertThat(attempt(inventedKey(), other).code()).isEqualTo(ErrorCode.UNAUTHENTICATED);
		clock.advance(Duration.ofSeconds(60));
		assertThat(attempt(inventedKey(), flooding).code()).isEqualTo(ErrorCode.UNAUTHENTICATED);
	}

	@Test
	void docs08_7_failuresAreAuditedOncePerAddressAndHour() throws Exception {
		Instant start = Instant.now().minusSeconds(5);
		// Two crossings of the limit in two different minutes of the same hour.
		long millisIntoHour = clock.millis() % 3_600_000;
		if (millisIntoHour > 3_600_000 - 180_000) {
			clock.advance(Duration.ofMillis(3_600_000 - millisIntoHour + 1_000));
		}
		floodFrom(flooding);
		assertThat(attempt(inventedKey(), flooding).code()).isEqualTo(ErrorCode.RATE_LIMITED);
		clock.advance(Duration.ofSeconds(60));
		floodFrom(flooding);

		try (Connection system = PostgresTestDatabase.connect(Role.SYSTEM);
				PreparedStatement statement = system.prepareStatement("""
						SELECT count(*) FROM audit_log
						WHERE action = 'AUTHENTICATION_FAILURES_BY_IP' AND resource_id = ? AND created_at >= ?
						""")) {
			statement.setString(1, flooding.getHostAddress());
			statement.setObject(2, Timestamp.from(start));
			var rs = statement.executeQuery();
			rs.next();
			assertThat(rs.getLong(1)).isOne();
		}
	}

}
