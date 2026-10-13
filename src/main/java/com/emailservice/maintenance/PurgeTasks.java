package com.emailservice.maintenance;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.emailservice.common.config.RoleConditions.ConditionalOnWorkerRole;
import com.emailservice.common.scheduling.PeriodicTask;

/** The purges of docs/05 §5 as worker tasks. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWorkerRole
class PurgeTasks {

	@Bean
	PeriodicTask purgeVariablesTask(RetentionPurges purges) {
		return task("purgeVariables", Duration.ofMinutes(15), Duration.ofMinutes(15), purges::purgeVariables);
	}

	@Bean
	PeriodicTask purgeIdempotencyKeysTask(RetentionPurges purges) {
		return task("purgeIdempotencyKeys", Duration.ofHours(1), Duration.ofHours(1), purges::purgeIdempotencyKeys);
	}

	@Bean
	PeriodicTask purgeExpiredDataTask(RetentionPurges purges) {
		// Daily, but first ten minutes after startup: deploys more often than daily would starve it.
		return task("purgeExpiredData", Duration.ofDays(1), Duration.ofMinutes(10), purges::purgeExpiredData);
	}

	private static PeriodicTask task(String name, Duration interval, Duration initialDelay, Runnable work) {
		return new PeriodicTask() {

			@Override
			public String name() {
				return name;
			}

			@Override
			public Duration interval() {
				return interval;
			}

			@Override
			public Duration initialDelay() {
				return initialDelay;
			}

			@Override
			public void run() {
				work.run();
			}
		};
	}

}
