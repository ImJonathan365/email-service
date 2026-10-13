package com.emailservice.sending;

import java.time.Duration;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.emailservice.common.api.ApiException;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Per-tenant rate limit and daily quota (FR-21), counted in PostgreSQL so every API instance shares
 * them (AC-21.2). Called after every validation and inside the transaction of the INSERT: the
 * 429 rolls the increment back, so a rejected or failed request never consumes quota and the
 * counter commits only with its message (AC-21.5). Idempotent repeats never reach it (AC-08.7).
 */
@Component
class AcceptanceRateLimiter {

	private final JdbcClient jdbc;

	private final RateLimitAudit audit;

	private final MeterRegistry meters;

	AcceptanceRateLimiter(JdbcClient jdbc, RateLimitAudit audit, MeterRegistry meters) {
		this.jdbc = jdbc;
		this.audit = audit;
		this.meters = meters;
	}

	private record Limits(String slug, int perMinute, int dailyQuota, String timezone) {
	}

	private record Window(int count, double secondsLeft) {
	}

	void consume(UUID tenantId) {
		// Read at every request: a change of limits applies without a redeploy (AC-21.3).
		Limits limits = jdbc.sql("SELECT slug, rate_limit_per_minute, daily_quota, timezone FROM tenant WHERE id = :id")
			.param("id", tenantId)
			.query((rs, row) -> new Limits(rs.getString("slug"), rs.getInt("rate_limit_per_minute"),
					rs.getInt("daily_quota"), rs.getString("timezone")))
			.single();

		Window minute = increment(tenantId, "MINUTE", "date_trunc('minute', now())", "interval '1 minute'", null);
		if (minute.count() > limits.perMinute()) {
			throw reject(tenantId, limits, "MINUTE", limits.perMinute(), minute);
		}
		// The day follows the tenant's calendar, not UTC.
		Window day = increment(tenantId, "DAY", "date_trunc('day', now() AT TIME ZONE :tz) AT TIME ZONE :tz",
				"interval '1 day'", limits.timezone());
		if (day.count() > limits.dailyQuota()) {
			throw reject(tenantId, limits, "DAY", limits.dailyQuota(), day);
		}
	}

	private Window increment(UUID tenantId, String kind, String windowStart, String length, String timezone) {
		var statement = jdbc.sql("""
				INSERT INTO rate_limit_counter (tenant_id, window_kind, window_start, count)
				VALUES (:tenantId, :kind, %s, 1)
				ON CONFLICT (tenant_id, window_kind, window_start)
				    DO UPDATE SET count = rate_limit_counter.count + 1
				RETURNING count, extract(epoch FROM window_start + %s - now()) AS seconds_left
				""".formatted(windowStart, length))
			.param("tenantId", tenantId)
			.param("kind", kind);
		if (timezone != null) {
			statement = statement.param("tz", timezone);
		}
		return statement.query((rs, row) -> new Window(rs.getInt("count"), rs.getDouble("seconds_left"))).single();
	}

	private ApiException reject(UUID tenantId, Limits limits, String window, int limit, Window state) {
		meters.counter("email.rate.limited", "tenant", limits.slug(), "window", window).increment();
		audit.recordOncePerHour(tenantId, window, limit);
		String detail = "MINUTE".equals(window) ? "The tenant's rate limit of " + limit + " emails per minute was reached."
				: "The tenant's daily quota of " + limit + " emails was reached.";
		return ApiException.rateLimited(detail, Duration.ofMillis((long) (state.secondsLeft() * 1000)));
	}

}
