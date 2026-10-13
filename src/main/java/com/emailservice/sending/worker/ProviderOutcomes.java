package com.emailservice.sending.worker;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

import org.springframework.stereotype.Component;

import com.emailservice.common.config.RoleConditions.ConditionalOnWorkerRole;

/** Results of the provider calls of the last five minutes, for the provider health indicator (AC-24.1). */
@Component
@ConditionalOnWorkerRole
class ProviderOutcomes {

	static final Duration WINDOW = Duration.ofMinutes(5);

	enum Outcome {

		SUCCESS, TRANSIENT, PERMANENT, CONFIGURATION

	}

	private record Entry(Instant at, Outcome outcome) {
	}

	record Summary(int calls, int transientFailures, int configurationErrors) {
	}

	private final Deque<Entry> entries = new ArrayDeque<>();

	private final Clock clock;

	ProviderOutcomes(Clock clock) {
		this.clock = clock;
	}

	synchronized void record(Outcome outcome) {
		entries.addLast(new Entry(clock.instant(), outcome));
		expire();
	}

	synchronized Summary lastFiveMinutes() {
		expire();
		int transientFailures = 0;
		int configuration = 0;
		for (Entry entry : entries) {
			if (entry.outcome() == Outcome.TRANSIENT) {
				transientFailures++;
			}
			else if (entry.outcome() == Outcome.CONFIGURATION) {
				configuration++;
			}
		}
		return new Summary(entries.size(), transientFailures, configuration);
	}

	private void expire() {
		Instant oldest = clock.instant().minus(WINDOW);
		while (!entries.isEmpty() && entries.peekFirst().at().isBefore(oldest)) {
			entries.removeFirst();
		}
	}

}
