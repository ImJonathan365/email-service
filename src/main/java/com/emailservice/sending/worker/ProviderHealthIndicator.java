package com.emailservice.sending.worker;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

import com.emailservice.common.config.RoleConditions.ConditionalOnWorkerRole;

/**
 * The "provider" indicator of /actuator/health (AC-24.1), from the worker's own results of the
 * last five minutes; it never calls the provider. DOWN while the circuit breaker is open or after
 * a configuration error; DEGRADED when at least half of five or more calls failed transiently.
 * Not part of liveness or readiness: an unhealthy provider must not restart a healthy worker.
 */
@Component("provider")
@ConditionalOnWorkerRole
class ProviderHealthIndicator implements HealthIndicator {

	static final Status DEGRADED = new Status("DEGRADED");

	private final ProviderOutcomes outcomes;

	private final CircuitBreaker breaker;

	ProviderHealthIndicator(ProviderOutcomes outcomes, CircuitBreaker breaker) {
		this.outcomes = outcomes;
		this.breaker = breaker;
	}

	@Override
	public Health health() {
		ProviderOutcomes.Summary summary = outcomes.lastFiveMinutes();
		CircuitBreaker.State state = breaker.state();
		Health.Builder builder;
		if (state != CircuitBreaker.State.CLOSED || summary.configurationErrors() > 0) {
			builder = Health.down();
		}
		else if (summary.calls() >= 5 && summary.transientFailures() * 2 >= summary.calls()) {
			builder = Health.status(DEGRADED);
		}
		else {
			builder = Health.up();
		}
		return builder.withDetail("circuitBreaker", state.name())
			.withDetail("callsLast5Minutes", summary.calls())
			.withDetail("transientFailures", summary.transientFailures())
			.withDetail("configurationErrors", summary.configurationErrors())
			.build();
	}

}
