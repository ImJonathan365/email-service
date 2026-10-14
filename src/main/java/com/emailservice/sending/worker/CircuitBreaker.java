package com.emailservice.sending.worker;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Hand-written breaker of AC-13.9: after N consecutive transient failures, or at once on a provider
 * configuration error, the worker stops claiming messages for the open period, then lets a single
 * message through as a probe. Messages are never failed because of it; they just wait in QUEUED.
 */
final class CircuitBreaker {

	enum State {

		CLOSED, OPEN, HALF_OPEN

	}

	private final int failureThreshold;

	private final Duration openFor;

	private final Clock clock;

	private State state = State.CLOSED;

	private int consecutiveFailures;

	private Instant openUntil = Instant.MIN;

	private boolean probeInFlight;

	/** When it last left CLOSED; kept while it reopens after failed probes (NFR-11 alert). */
	private Instant openSince;

	CircuitBreaker(int failureThreshold, Duration openFor, Clock clock) {
		this.failureThreshold = failureThreshold;
		this.openFor = openFor;
		this.clock = clock;
	}

	/** How many messages the worker may claim now, at most {@code freeSlots}. */
	synchronized int permittedClaims(int freeSlots) {
		if (state == State.OPEN && !clock.instant().isBefore(openUntil)) {
			state = State.HALF_OPEN;
			probeInFlight = false;
		}
		return switch (state) {
			case CLOSED -> freeSlots;
			case OPEN -> 0;
			case HALF_OPEN -> {
				if (probeInFlight || freeSlots == 0) {
					yield 0;
				}
				probeInFlight = true;
				yield 1;
			}
		};
	}

	synchronized void onSuccess() {
		state = State.CLOSED;
		consecutiveFailures = 0;
		probeInFlight = false;
		openSince = null;
	}

	synchronized void onTransientFailure() {
		consecutiveFailures++;
		if (state == State.HALF_OPEN || consecutiveFailures >= failureThreshold) {
			open();
		}
	}

	/** A broken provider setup fails every message alike: no point in waiting for N failures. */
	synchronized void onConfigurationError() {
		open();
	}

	/** A probe that ended without telling us about the provider (e.g. a held tenant) frees its slot. */
	synchronized void onInconclusive() {
		probeInFlight = false;
	}

	/** Open and not yet due for a probe: messages already claimed must not reach the provider. */
	synchronized boolean blocksSending() {
		return state == State.OPEN && clock.instant().isBefore(openUntil);
	}

	synchronized State state() {
		return state;
	}

	/** How long it has not been closed; zero while closed. */
	synchronized Duration openFor() {
		return openSince == null ? Duration.ZERO : Duration.between(openSince, clock.instant());
	}

	private void open() {
		if (openSince == null) {
			openSince = clock.instant();
		}
		state = State.OPEN;
		openUntil = clock.instant().plus(openFor);
		probeInFlight = false;
	}

}
