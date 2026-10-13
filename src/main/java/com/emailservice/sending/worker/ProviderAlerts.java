package com.emailservice.sending.worker;

import java.time.Duration;

import org.springframework.stereotype.Component;

import com.emailservice.common.alert.Alerts;
import com.emailservice.common.config.RoleConditions.ConditionalOnWorkerRole;
import com.emailservice.common.scheduling.PeriodicTask;

/** NFR-11: the circuit breaker open for more than 10 minutes means the provider is really down. */
@Component
@ConditionalOnWorkerRole
class ProviderAlerts implements PeriodicTask {

	static final Duration OPEN_ALERT_AFTER = Duration.ofMinutes(10);

	private final CircuitBreaker breaker;

	private final Alerts alerts;

	ProviderAlerts(CircuitBreaker breaker, Alerts alerts) {
		this.breaker = breaker;
		this.alerts = alerts;
	}

	@Override
	public String name() {
		return "providerAlerts";
	}

	@Override
	public Duration interval() {
		return Duration.ofSeconds(30);
	}

	@Override
	public void run() {
		Duration open = breaker.openFor();
		if (open.compareTo(OPEN_ALERT_AFTER) > 0) {
			alerts.raise("CIRCUIT_BREAKER_OPEN", "provider",
					"The provider circuit breaker has not been closed for " + open.toMinutes() + " minutes");
		}
	}

}
