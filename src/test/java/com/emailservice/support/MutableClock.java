package com.emailservice.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock that tests move by hand. Fixed for unit tests; in the shared integration context it
 * follows the system time plus whatever a test advanced, and {@link #reset()} undoes that.
 */
public final class MutableClock extends Clock {

	private final Instant fixedStart;

	private volatile Duration offset = Duration.ZERO;

	/** Starts at a fixed instant and only moves with {@link #advance(Duration)}. */
	public MutableClock() {
		this(Instant.parse("2026-01-01T00:00:00Z"));
	}

	private MutableClock(Instant fixedStart) {
		this.fixedStart = fixedStart;
	}

	/** The system time, shifted by {@link #advance(Duration)}: signatures and windows keep working. */
	public static MutableClock followingSystemTime() {
		return new MutableClock(null);
	}

	public void advance(Duration duration) {
		offset = offset.plus(duration);
	}

	public void reset() {
		offset = Duration.ZERO;
	}

	@Override
	public Instant instant() {
		return (fixedStart == null ? Instant.now() : fixedStart).plus(offset);
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return this;
	}

}
