package com.emailservice.maintenance;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.emailservice.common.alert.Alerts;
import com.emailservice.common.config.AppProperties;
import com.emailservice.common.config.RoleConditions.ConditionalOnWorkerRole;
import com.emailservice.common.persistence.SystemDataSource;
import com.emailservice.common.scheduling.PeriodicTask;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;

/**
 * emitQueueMetrics (docs/05 §5), every 30 s on the worker: the gauges of AC-24.2 that need a
 * query, and the NFR-11 alerts on the queue's age and the share of FAILED messages.
 */
@Component
@ConditionalOnWorkerRole
class OperationalMetrics implements PeriodicTask {

	/** The FAILED share only alerts with enough messages to mean something. */
	static final int FAILED_RATE_MIN_MESSAGES = 20;

	static final double FAILED_RATE_ALERT = 0.05;

	private final JdbcClient jdbc;

	private final QueueState queue;

	private final Alerts alerts;

	private final long queueAgeAlertSeconds;

	private final AtomicLong pending = new AtomicLong();

	private final AtomicLong oldestSeconds = new AtomicLong();

	private final MultiGauge heldByTenant;

	private final MultiGauge messagesByStatus;

	OperationalMetrics(@SystemDataSource JdbcClient jdbc, QueueState queue, Alerts alerts, MeterRegistry meters,
			AppProperties properties) {
		this.jdbc = jdbc;
		this.queue = queue;
		this.alerts = alerts;
		this.queueAgeAlertSeconds = properties.worker().queueAgeAlertSeconds();
		Gauge.builder("email.queue.depth", pending, AtomicLong::get)
			.description("QUEUED messages a worker could claim now")
			.register(meters);
		Gauge.builder("email.queue.oldest.seconds", oldestSeconds, AtomicLong::get)
			.description("Age of the oldest eligible message")
			.register(meters);
		this.heldByTenant = MultiGauge.builder("email.queue.held")
			.description("QUEUED messages held by a suspended or paused tenant")
			.register(meters);
		this.messagesByStatus = MultiGauge.builder("email.messages")
			.description("Messages created in the last 24 hours, by status")
			.register(meters);
	}

	private record Outcomes(long failed, long done) {
	}

	@Override
	public String name() {
		return "emitQueueMetrics";
	}

	@Override
	public Duration interval() {
		return Duration.ofSeconds(30);
	}

	@Override
	public void run() {
		QueueState.Snapshot snapshot = queue.eligible();
		pending.set(snapshot.pending());
		oldestSeconds.set(snapshot.oldestSeconds());
		if (snapshot.oldestSeconds() > queueAgeAlertSeconds) {
			alerts.raise("QUEUE_AGE", "queue", "The oldest eligible message has waited " + snapshot.oldestSeconds()
					+ " s (threshold " + queueAgeAlertSeconds + " s); " + snapshot.pending() + " pending");
		}

		heldByTenant.register(jdbc.sql("""
				SELECT t.slug, count(m.id) AS held
				FROM tenant t JOIN email_message m ON m.tenant_id = t.id AND m.status = 'QUEUED'
				WHERE t.status <> 'ACTIVE' OR t.sending_paused_at IS NOT NULL
				GROUP BY t.slug
				""")
			.query((rs, row) -> MultiGauge.Row.of(Tags.of("tenant", rs.getString("slug")), rs.getLong("held")))
			.list(), true);

		messagesByStatus.register(jdbc.sql("""
				SELECT status, count(*) AS n FROM email_message WHERE created_at > now() - interval '24 hours'
				GROUP BY status
				""").query((rs, row) -> MultiGauge.Row.of(Tags.of("status", rs.getString("status")), rs.getLong("n")))
			.list(), true);

		Outcomes lastHour = jdbc.sql("""
				SELECT count(*) FILTER (WHERE status = 'FAILED') AS failed,
				       count(*) FILTER (WHERE status IN ('SENT','DELIVERED','BOUNCED','COMPLAINED','FAILED')) AS done
				FROM email_message WHERE created_at > now() - interval '1 hour'
				""").query((rs, row) -> new Outcomes(rs.getLong("failed"), rs.getLong("done"))).single();
		if (lastHour.done() >= FAILED_RATE_MIN_MESSAGES && (double) lastHour.failed() / lastHour.done() > FAILED_RATE_ALERT) {
			alerts.raise("FAILED_RATE", "all-tenants", lastHour.failed() + " of " + lastHour.done()
					+ " messages of the last hour are FAILED (threshold 5 %)");
		}
	}

}
