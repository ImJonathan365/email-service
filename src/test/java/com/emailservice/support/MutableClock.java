package com.emailservice.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock that tests move by hand. Fixed for unit tests; in the shared integration context it
 * follows the system time plus whatever a test advanced, unless a test pins it with
 * {@link #fixAt(Instant)}. {@link #reset()} undoes both, and every test that moves the shared
 * clock must reset it.
 */
public final class MutableClock extends Clock {

	private volatile Instant fixedStart;

	private volatile Duration offset = Duration.ZERO;

	private final boolean following;

	private final Instant initialStart;

	/** Starts at a fixed instant and only moves with {@link #advance(Duration)}. */
	public MutableClock() {
		this(Instant.parse("2026-01-01T00:00:00Z"));
	}

	private MutableClock(Instant fixedStart) {
		this.fixedStart = fixedStart;
		this.following = fixedStart == null;
		this.initialStart = fixedStart;
	}

	/** The system time, shifted by {@link #advance(Duration)}: signatures and windows keep working. */
	public static MutableClock followingSystemTime() {
		return new MutableClock(null);
	}

	public void advance(Duration duration) {
		offset = offset.plus(duration);
	}

	/** Stops following the system time: the clock reads {@code instant} until advanced or reset. */
	public void fixAt(Instant instant) {
		fixedStart = instant;
		offset = Duration.ZERO;
	}

	/** Back to the system time (shared clock) or the initial instant (unit-test clock), no offset. */
	public void reset() {
		fixedStart = following ? null : initialStart;
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
