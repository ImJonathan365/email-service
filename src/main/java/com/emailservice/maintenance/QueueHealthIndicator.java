package com.emailservice.maintenance;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

import com.emailservice.common.config.AppProperties;

/**
 * The "queue" indicator of /actuator/health (AC-24.4): DEGRADED, answered with HTTP 200, once
 * the oldest eligible message waits longer than QUEUE_AGE_ALERT_SECONDS. A slow queue must not
 * make the orchestrator restart healthy instances, so it is in neither liveness nor readiness.
 */
@Component("queue")
class QueueHealthIndicator implements HealthIndicator {

	static final Status DEGRADED = new Status("DEGRADED");

	private final QueueState queue;

	private final long thresholdSeconds;

	QueueHealthIndicator(QueueState queue, AppProperties properties) {
		this.queue = queue;
		this.thresholdSeconds = properties.worker().queueAgeAlertSeconds();
	}

	@Override
	public Health health() {
		QueueState.Snapshot snapshot = queue.eligible();
		return (snapshot.oldestSeconds() > thresholdSeconds ? Health.status(DEGRADED) : Health.up())
			.withDetail("pending", snapshot.pending())
			.withDetail("oldestSeconds", snapshot.oldestSeconds())
			.withDetail("alertAfterSeconds", thresholdSeconds)
			.build();
	}

}
