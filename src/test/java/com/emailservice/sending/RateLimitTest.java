package com.emailservice.sending;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.emailservice.support.AuditRows;
import com.emailservice.support.IntegrationTest;
import com.emailservice.support.PostgresTestDatabase;
import com.emailservice.support.PostgresTestDatabase.Role;

import io.micrometer.core.instrument.MeterRegistry;

/** FR-21: per-tenant rate limit and daily quota, shared through PostgreSQL. */
class RateLimitTest extends IntegrationTest {

	@Autowired
	MeterRegistry meters;

	SendingTestSupport a;

	@BeforeEach
	void setUp() throws Exception {
		a = new SendingTestSupport(http(), "SECURITY");
		awayFromTheEndOfAMinute();
	}

	/** Minute windows are real ones: never start a test in the last seconds of a minute. */
	static void awayFromTheEndOfAMinute() throws InterruptedException {
		int second = LocalTime.now().getSecond();
		if (second >= 50) {
			Thread.sleep((61 - second) * 1000L);
		}
	}

	void limits(String body) throws Exception {
		a.admin.patchTenant(a.tenant, body);
	}

	HttpResponse<String> send() throws Exception {
		return a.send(a.body(""), null);
	}

	static long counter(UUID tenantId, String kind) throws Exception {
		try (Connection system = PostgresTestDatabase.connect(Role.SYSTEM);
				PreparedStatement statement = system.prepareStatement(
						"SELECT coalesce(sum(count), 0) FROM rate_limit_counter WHERE tenant_id = ? AND window_kind = ?")) {
			statement.setObject(1, tenantId);
			statement.setString(2, kind);
			try (ResultSet rs = statement.executeQuery()) {
				rs.next();
				return rs.getLong(1);
			}
		}
	}

	@Test
	void ac_21_1_overTheMinuteLimitAnswers429WithRetryAfterAndConsumesNothing() throws Exception {
		limits("{\"rateLimitPerMinute\": 3}");
		for (int i = 0; i < 3; i++) {
			assertThat(send().statusCode()).isEqualTo(202);
		}

		HttpResponse<String> limited = send();

		assertThat(limited.statusCode()).isEqualTo(429);
		assertThat(limited.body()).contains("RATE_LIMITED");
		long retryAfter = Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow());
		assertThat(retryAfter).isBetween(1L, 60L);
		assertThat(counter(a.tenant.id(), "MINUTE")).as("the rejected request rolled back").isEqualTo(3);
		assertThat(SendingTestSupport.countMessages(a.tenant.id())).isEqualTo(3);
	}

	@Test
	void ac_21_3_limitsChangeWithoutARedeploy() throws Exception {
		limits("{\"rateLimitPerMinute\": 1}");
		assertThat(send().statusCode()).isEqualTo(202);
		assertThat(send().statusCode()).isEqualTo(429);

		limits("{\"rateLimitPerMinute\": 10}");

		assertThat(send().statusCode()).isEqualTo(202);
	}

	@Test
	void ac_21_5_rejectedRequestsAndIdempotentRepeatsConsumeNoQuota() throws Exception {
		limits("{\"rateLimitPerMinute\": 2}");
		for (int i = 0; i < 3; i++) {
			assertThat(a.send(a.body("").replace("ana@example.test", "not-an-address"), null).statusCode())
				.isEqualTo(422);
		}
		String key = "order-" + UUID.randomUUID();
		assertThat(a.send(a.body(""), key).statusCode()).isEqualTo(202);
		assertThat(a.send(a.body(""), key).statusCode()).as("idempotent repeat").isEqualTo(200);
		assertThat(a.send(a.body(""), key).statusCode()).isEqualTo(200);

		assertThat(send().statusCode()).as("second and last of the minute").isEqualTo(202);
		assertThat(send().statusCode()).isEqualTo(429);
	}

	@Test
	void ac_21_5_aMessageCreatedAsSuppressedDoesConsumeQuota() throws Exception {
		limits("{\"rateLimitPerMinute\": 1}");
		String suppressed = "blocked-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
		SendingTestSupport.suppress(suppressed, "GLOBAL", null, "HARD_BOUNCE");

		HttpResponse<String> failed = a.send(a.bodyTo(suppressed, ""), null);
		assertThat(failed.statusCode()).isEqualTo(202);
		assertThat(SendingTestSupport.json(failed).get("status").stringValue()).isEqualTo("FAILED");

		assertThat(send().statusCode()).isEqualTo(429);
	}

	@Test
	void ac_21_2_theCounterIsSharedThroughTheDatabase() throws Exception {
		limits("{\"rateLimitPerMinute\": 5}");
		// Another API instance already used the whole minute.
		try (Connection system = PostgresTestDatabase.connect(Role.SYSTEM);
				PreparedStatement statement = system.prepareStatement("""
						INSERT INTO rate_limit_counter (tenant_id, window_kind, window_start, count)
						VALUES (?, 'MINUTE', date_trunc('minute', now()), 5)
						""")) {
			statement.setObject(1, a.tenant.id());
			statement.executeUpdate();
		}

		assertThat(send().statusCode()).isEqualTo(429);
	}

	@Test
	void ac_21_1_theDailyQuotaFollowsTheTenantsCalendar() throws Exception {
		ZoneId zone = ZoneId.of("Pacific/Kiritimati");
		limits("{\"dailyQuota\": 2, \"timezone\": \"Pacific/Kiritimati\"}");
		// A full counter of the tenant's previous day does not count today.
		try (Connection system = PostgresTestDatabase.connect(Role.SYSTEM);
				PreparedStatement statement = system.prepareStatement("""
						INSERT INTO rate_limit_counter (tenant_id, window_kind, window_start, count)
						VALUES (?, 'DAY', (date_trunc('day', now() AT TIME ZONE 'Pacific/Kiritimati') - interval '1 day')
						        AT TIME ZONE 'Pacific/Kiritimati', 1000)
						""")) {
			statement.setObject(1, a.tenant.id());
			statement.executeUpdate();
		}
		assertThat(send().statusCode()).isEqualTo(202);
		assertThat(send().statusCode()).isEqualTo(202);

		HttpResponse<String> limited = send();

		assertThat(limited.statusCode()).isEqualTo(429);
		ZonedDateTime now = ZonedDateTime.now(zone);
		long untilMidnight = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(zone)).toSeconds();
		long retryAfter = Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow());
		assertThat(retryAfter).isBetween(untilMidnight - 5, untilMidnight + 5);
		assertThat(limited.body()).contains("daily quota");
	}

	@Test
	void ac_21_4_rejectionsAreCountedAndAuditedOncePerWindowAndHour() throws Exception {
		limits("{\"rateLimitPerMinute\": 1}");
		double before = meters.counter("email.rate.limited", "tenant", a.tenant.slug(), "window", "MINUTE").count();
		send();
		for (int i = 0; i < 5; i++) {
			assertThat(send().statusCode()).isEqualTo(429);
		}

		assertThat(meters.counter("email.rate.limited", "tenant", a.tenant.slug(), "window", "MINUTE").count() - before)
			.isEqualTo(5.0);
		assertThat(AuditRows.forTenant(a.tenant.id())).filteredOn(row -> row.action().equals("TENANT_RATE_LIMITED"))
			.singleElement()
			.satisfies(row -> {
				assertThat(row.actorType()).isEqualTo("SYSTEM");
				assertThat(row.metadata()).contains("MINUTE");
			});
	}

}
