package com.emailservice.sending;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.emailservice.audit.Actor;
import com.emailservice.audit.AuditAction;
import com.emailservice.audit.AuditLog;

/**
 * Aggregated audit of rate-limit rejections (AC-21.4, owner 2026-10-12): one row per tenant,
 * window kind and hour, never one per rejection. It runs in its own transaction because the
 * rejected request's transaction rolls back; the exact count lives in email_rate_limited_total.
 * The hour is the application clock's, kept in the row, so it does not depend on database time.
 */
@Component
class RateLimitAudit {

	private static final Actor RATE_LIMITER = new Actor(Actor.Type.SYSTEM, "rate-limiter", null, null);

	private final JdbcClient jdbc;

	private final AuditLog auditLog;

	RateLimitAudit(JdbcClient jdbc, AuditLog auditLog) {
		this.jdbc = jdbc;
		this.auditLog = auditLog;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordOncePerHour(UUID tenantId, String window, int limit, Instant now) {
		String hour = now.truncatedTo(ChronoUnit.HOURS).toString();
		// Serializes concurrent first rejections of this tenant and window across instances.
		jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:key))")
			.param("key", "rate-limit-audit:" + tenantId + ":" + window)
			.query()
			.listOfRows();
		boolean alreadyThisHour = jdbc.sql("""
				SELECT count(*) FROM audit_log
				WHERE tenant_id = :tenantId AND action = 'TENANT_RATE_LIMITED'
				  AND metadata ->> 'window' = :window AND metadata ->> 'hour' = :hour
				""")
			.param("tenantId", tenantId)
			.param("window", window)
			.param("hour", hour)
			.query(Long.class)
			.single() > 0;
		if (!alreadyThisHour) {
			auditLog.record(jdbc, RATE_LIMITER, AuditAction.TENANT_RATE_LIMITED, tenantId, "tenant", tenantId.toString(),
					Map.of("window", window, "limit", limit, "hour", hour));
		}
	}

}
