package com.emailservice.tenancy.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.net.InetAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.emailservice.audit.AuditLog;
import com.emailservice.common.alert.Alerts;
import com.emailservice.common.api.ApiException;
import com.emailservice.common.api.ErrorCode;
import com.emailservice.common.config.AppProperties;
import com.emailservice.common.persistence.SystemDataSource;
import com.emailservice.support.AdminApi;
import com.emailservice.support.IntegrationTest;
import com.emailservice.support.MutableClock;
import com.emailservice.support.PostgresTestDatabase;
import com.emailservice.support.PostgresTestDatabase.Role;
import com.emailservice.tenancy.AuthenticatedApiKey;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * docs/08 §4 (owner, 2026-10-12): an IP past the failure limit gets 429 only for requests without
 * a known key prefix, without touching the database; known prefixes keep the full check.
 *
 * Deterministic: each test builds its own authenticator, failure windows, prefix set and alerts
 * on its own clock, fixed in the middle of a minute and of an hour, and moves it explicitly. The
 * addresses come from a counter and never repeat, and nothing time-based is shared with other
 * tests. Only the database, the key lookup, the audit log and the metrics are the real beans.
 */
class AuthIpLimitTest extends IntegrationTest {

	/** Thirty seconds into a minute, twenty minutes into an hour: no window boundary is near. */
	static final Instant START = Instant.parse("2031-03-04T10:20:30Z");

	static final AtomicInteger ADDRESSES = new AtomicInteger();

	@Autowired
	ApiKeyLookup lookup;

	@Autowired
	AuditLog auditLog;

	@Autowired
	MeterRegistry meters;

	@Autowired
	AppProperties properties;

	@Autowired
	@SystemDataSource
	JdbcClient systemJdbc;

	MutableClock clock;

	AuthFailuresByIp failures;

	ApiKeyAuthenticator authenticator;

	AdminApi admin;

	InetAddress flooding;

	InetAddress other;

	@BeforeEach
	void setUp() throws Exception {
		clock = new MutableClock();
		clock.fixAt(START);
		failures = new AuthFailuresByIp(properties, clock);
		authenticator = new ApiKeyAuthenticator(lookup, auditLog, meters, new KnownKeyPrefixes(systemJdbc, clock),
				failures, new Alerts(clock));
		admin = new AdminApi(http());
		flooding = nextAddress("203.0.113.");
		other = nextAddress("198.51.100.");
	}

	/** Documentation ranges (RFC 5737); 250 per range per run, far more than the tests use. */
	static InetAddress nextAddress(String range) throws Exception {
		return InetAddress.getByName(range + (1 + ADDRESSES.getAndIncrement() % 250));
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
			assertThat(limited.retryAfterSeconds()).as("until the next minute").isEqualTo(30L);
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

		// The next refresh drops the prefix. It is also the next minute, so the address floods again.
		clock.advance(KnownKeyPrefixes.REFRESH_EVERY.plusSeconds(1));
		floodFrom(flooding);
		assertThat(attempt(key.bearer(), flooding).code()).isEqualTo(ErrorCode.RATE_LIMITED);
	}

	@Test
	void docs08_4_anotherAddressAndTheNextMinuteAreUnaffected() {
		floodFrom(flooding);

		assertThat(attempt(inventedKey(), other).code()).isEqualTo(ErrorCode.UNAUTHENTICATED);
		assertThat(attempt(inventedKey(), flooding).code()).isEqualTo(ErrorCode.RATE_LIMITED);
		clock.advance(Duration.ofSeconds(60));
		assertThat(attempt(inventedKey(), flooding).code()).as("a new window").isEqualTo(ErrorCode.UNAUTHENTICATED);
	}

	@Test
	void docs08_7_failuresAreAuditedOncePerAddressAndHour() throws Exception {
		long before = auditRows(flooding);
		// Two crossings of the limit, in two minutes of the same hour.
		floodFrom(flooding);
		assertThat(attempt(inventedKey(), flooding).code()).isEqualTo(ErrorCode.RATE_LIMITED);
		clock.advance(Duration.ofSeconds(60));
		floodFrom(flooding);

		assertThat(auditRows(flooding) - before).isOne();

		// The next hour audits again.
		clock.advance(Duration.ofHours(1));
		floodFrom(flooding);
		assertThat(auditRows(flooding) - before).isEqualTo(2);
	}

	/** Counted before and after, so rows other tests wrote for the same address never matter. */
	static long auditRows(InetAddress ip) throws Exception {
		try (Connection system = PostgresTestDatabase.connect(Role.SYSTEM);
				PreparedStatement statement = system.prepareStatement(
						"SELECT count(*) FROM audit_log WHERE action = 'AUTHENTICATION_FAILURES_BY_IP' AND resource_id = ?")) {
			statement.setString(1, ip.getHostAddress());
			var rs = statement.executeQuery();
			rs.next();
			return rs.getLong(1);
		}
	}

}
