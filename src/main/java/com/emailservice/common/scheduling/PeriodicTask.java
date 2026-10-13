package com.emailservice.common.scheduling;

import java.time.Duration;

/**
 * A worker task that runs every {@link #interval()} (docs/05 §5). Implementations take their own
 * pg_try_advisory_xact_lock, so with several worker instances only one runs each round.
 */
public interface PeriodicTask {

	String name();

	Duration interval();

	/** First run after startup; daily tasks start sooner so frequent deploys never starve them. */
	default Duration initialDelay() {
		return interval();
	}

	void run();

}
