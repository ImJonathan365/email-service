package com.emailservice.abuse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.emailservice.audit.Actor;
import com.emailservice.audit.AuditAction;
import com.emailservice.audit.AuditLog;
import com.emailservice.common.alert.Alerts;
import com.emailservice.common.config.AppProperties;
import com.emailservice.common.config.RoleConditions.ConditionalOnWorkerRole;
import com.emailservice.common.persistence.SystemDataSource;
import com.emailservice.common.scheduling.PeriodicTask;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * detectAnomalies (FR-34): every 5 minutes, per active tenant that is not already paused, pauses
 * sending on a volume spike, a high bounce rate or a high complaint rate. A paused tenant keeps its
 * queue (AC-34.4) until an administrator resumes it. The volume minimums are floors a tenant's
 * overrides can raise but never lower (AC-34.5), so few sends never trigger a pause.
 */
@Component
@ConditionalOnWorkerRole
class AnomalyDetector implements PeriodicTask {

	private static final Logger log = LoggerFactory.getLogger(AnomalyDetector.class);

	private static final Actor DETECTOR = new Actor(Actor.Type.SYSTEM, "anomaly-detector", null, null);

	static final int BOUNCE_MIN_RESULTS = 200;

	static final int COMPLAINT_MIN_DELIVERED = 1_000;

	static final int COMPLAINTS_IN_A_DAY = 3;

	static final int HISTORY_HOURS = 14 * 24;

	private final JdbcClient jdbc;

	private final AuditLog auditLog;

	private final Alerts alerts;

	private final MeterRegistry meters;

	private final JsonMapper jsonMapper;

	private final AppProperties.Tenancy defaults;

	AnomalyDetector(@SystemDataSource JdbcClient jdbc, AuditLog auditLog, Alerts alerts, MeterRegistry meters,
			JsonMapper jsonMapper, AppProperties properties) {
		this.jdbc = jdbc;
		this.auditLog = auditLog;
		this.alerts = alerts;
		this.meters = meters;
		this.jsonMapper = jsonMapper;
		this.defaults = properties.tenancy();
	}

	@Override
	public String name() {
		return "detectAnomalies";
	}

	@Override
	public Duration interval() {
		return Duration.ofMinutes(5);
	}

	@Override
	public void run() {
		evaluate();
	}

	record Stats(UUID tenantId, String slug, String overrides, long lastHour, long results24h, long bounced24h,
			long delivered7d, long complained7d, long complained24h) {
	}

	record Thresholds(double volumeMultiplier, int minHourly, double bouncePauseRate, double complaintPauseRate) {
	}

	record Decision(String reason, String observed, String threshold) {
	}

	/** One round; returns the slugs paused in it. */
	@Transactional("systemTransactionManager")
	List<String> evaluate() {
		Boolean acquired = jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtext('detectAnomalies'))")
			.query(Boolean.class)
			.single();
		if (!Boolean.TRUE.equals(acquired)) {
			return List.of();
		}
		Map<UUID, long[]> history = hourlyHistory();
		List<String> paused = new ArrayList<>();
		for (Stats stats : stats()) {
			Thresholds thresholds = thresholds(stats.overrides());
			decide(stats, thresholds, history.getOrDefault(stats.tenantId(), new long[0]))
				.filter(decision -> pause(stats, decision))
				.ifPresent(decision -> paused.add(stats.slug()));
		}
		return paused;
	}

	/** Complaints first: they hurt the shared reputation most; then bounces, then volume. */
	static Optional<Decision> decide(Stats stats, Thresholds thresholds, long[] hourlyCounts) {
		if (stats.complained24h() >= COMPLAINTS_IN_A_DAY) {
			return Optional.of(new Decision("COMPLAINT_RATE", stats.complained24h() + " complaints in 24 h",
					COMPLAINTS_IN_A_DAY + " in 24 h"));
		}
		if (stats.delivered7d() >= COMPLAINT_MIN_DELIVERED
				&& (double) stats.complained7d() / stats.delivered7d() > thresholds.complaintPauseRate()) {
			return Optional.of(new Decision("COMPLAINT_RATE",
					rate(stats.complained7d(), stats.delivered7d()) + " of " + stats.delivered7d() + " in 7 days",
					"> " + thresholds.complaintPauseRate()));
		}
		if (stats.results24h() >= BOUNCE_MIN_RESULTS
				&& (double) stats.bounced24h() / stats.results24h() > thresholds.bouncePauseRate()) {
			return Optional.of(new Decision("BOUNCE_RATE",
					rate(stats.bounced24h(), stats.results24h()) + " of " + stats.results24h() + " in 24 h",
					"> " + thresholds.bouncePauseRate()));
		}
		double limit = Math.max(thresholds.volumeMultiplier() * p95(hourlyCounts), thresholds.minHourly());
		if (stats.lastHour() > limit) {
			return Optional.of(new Decision("VOLUME_ANOMALY", stats.lastHour() + " accepted in the last hour",
					"> " + Math.round(limit)));
		}
		return Optional.empty();
	}

	/** p95 of the hourly counts of the last 14 days, hours without messages counted as zero. */
	static double p95(long[] hourlyCounts) {
		long[] all = Arrays.copyOf(hourlyCounts, HISTORY_HOURS);
		Arrays.sort(all);
		return all[(int) Math.ceil(0.95 * HISTORY_HOURS) - 1];
	}

	private Thresholds thresholds(String overrides) {
		JsonNode node = overrides == null ? null : jsonMapper.readTree(overrides);
		return new Thresholds(number(node, "volumeMultiplier", defaults.anomalyVolumeMultiplier()),
				// An override may raise the minimum, never lower it (AC-34.5).
				(int) Math.max(number(node, "minHourly", defaults.anomalyMinHourly()), defaults.anomalyMinHourly()),
				number(node, "bouncePauseRate", defaults.bouncePauseRate()),
				number(node, "complaintPauseRate", defaults.complaintPauseRate()));
	}

	private static double number(JsonNode node, String field, double fallback) {
		JsonNode value = node == null ? null : node.get(field);
		return value != null && value.isNumber() ? value.doubleValue() : fallback;
	}

	private boolean pause(Stats stats, Decision decision) {
		boolean paused = jdbc.sql("""
				UPDATE tenant SET sending_paused_at = now(), pause_reason = :reason, updated_at = now()
				WHERE id = :id AND status = 'ACTIVE' AND sending_paused_at IS NULL
				""")
			.param("reason", decision.reason())
			.param("id", stats.tenantId())
			.update() == 1;
		if (paused) {
			auditLog.record(jdbc, DETECTOR, AuditAction.TENANT_PAUSED, stats.tenantId(), "tenant",
					stats.tenantId().toString(), Map.of("reason", decision.reason(), "observed", decision.observed(),
							"threshold", decision.threshold()));
			meters.counter("email.tenant.paused", "reason", decision.reason()).increment();
			alerts.raise("TENANT_PAUSED", stats.slug(), "Tenant " + stats.slug() + " paused (" + decision.reason()
					+ "): " + decision.observed() + ", threshold " + decision.threshold());
			log.warn("Tenant {} paused by the anomaly detector: {}", stats.slug(), decision.reason());
		}
		return paused;
	}

	private List<Stats> stats() {
		return jdbc.sql("""
				SELECT t.id, t.slug, t.anomaly_overrides::text AS overrides,
				       count(m.id) FILTER (WHERE m.created_at > now() - interval '1 hour') AS last_hour,
				       count(m.id) FILTER (WHERE m.created_at > now() - interval '24 hours'
				           AND m.status IN ('SENT','DELIVERED','BOUNCED','COMPLAINED')) AS results_24h,
				       count(m.id) FILTER (WHERE m.created_at > now() - interval '24 hours'
				           AND m.status = 'BOUNCED') AS bounced_24h,
				       count(m.id) FILTER (WHERE m.status IN ('DELIVERED','COMPLAINED')) AS delivered_7d,
				       count(m.id) FILTER (WHERE m.status = 'COMPLAINED') AS complained_7d,
				       count(m.id) FILTER (WHERE m.created_at > now() - interval '24 hours'
				           AND m.status = 'COMPLAINED') AS complained_24h
				FROM tenant t
				LEFT JOIN email_message m ON m.tenant_id = t.id AND m.created_at > now() - interval '7 days'
				WHERE t.status = 'ACTIVE' AND t.sending_paused_at IS NULL
				GROUP BY t.id, t.slug, t.anomaly_overrides::text
				""")
			.query((rs, row) -> new Stats(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("overrides"),
					rs.getLong("last_hour"), rs.getLong("results_24h"), rs.getLong("bounced_24h"),
					rs.getLong("delivered_7d"), rs.getLong("complained_7d"), rs.getLong("complained_24h")))
			.list();
	}

	/** Accepted messages per tenant and hour over the 14 days before the current hour. */
	private Map<UUID, long[]> hourlyHistory() {
		Map<UUID, List<Long>> counts = new HashMap<>();
		jdbc.sql("""
				SELECT m.tenant_id, count(*) AS n
				FROM email_message m JOIN tenant t ON t.id = m.tenant_id
				WHERE t.status = 'ACTIVE' AND t.sending_paused_at IS NULL
				  AND m.created_at >= date_trunc('hour', now()) - interval '14 days'
				  AND m.created_at < date_trunc('hour', now())
				GROUP BY m.tenant_id, date_trunc('hour', m.created_at)
				""")
			.query((rs, row) -> counts.computeIfAbsent(rs.getObject("tenant_id", UUID.class), id -> new ArrayList<>())
				.add(rs.getLong("n")))
			.list();
		Map<UUID, long[]> history = new HashMap<>();
		counts.forEach((tenant, values) -> history.put(tenant, values.stream().mapToLong(Long::longValue).toArray()));
		return history;
	}

	private static String rate(long part, long total) {
		return String.format(Locale.ROOT, "%.2f%%", 100.0 * part / total);
	}

}
