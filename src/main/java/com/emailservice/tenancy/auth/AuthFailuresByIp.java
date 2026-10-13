package com.emailservice.tenancy.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.emailservice.common.config.AppProperties;

/**
 * Authentication failures per client IP in fixed one-minute windows, in memory per instance
 * (docs/08 §4). Bounded: entries of past minutes are dropped when the map grows.
 */
@Component
class AuthFailuresByIp {

	private static final int MAX_TRACKED_IPS = 100_000;

	private record Window(long minute, int failures) {
	}

	private final Map<String, Window> windows = new ConcurrentHashMap<>();

	private final Map<String, Long> auditedHour = new ConcurrentHashMap<>();

	private final int threshold;

	private final Clock clock;

	AuthFailuresByIp(AppProperties properties, Clock clock) {
		this.threshold = properties.http().authFailuresPerMinutePerIp();
		this.clock = clock;
	}

	int threshold() {
		return threshold;
	}

	boolean overThreshold(String ip) {
		Window window = windows.get(ip);
		return window != null && window.minute() == minute() && window.failures() >= threshold;
	}

	/** Counts a failure; true only for the failure that reaches the threshold in this window. */
	boolean recordFailure(String ip) {
		if (windows.size() > MAX_TRACKED_IPS) {
			long now = minute();
			windows.values().removeIf(window -> window.minute() != now);
		}
		long now = minute();
		Window updated = windows.merge(ip, new Window(now, 1),
				(old, fresh) -> old.minute() == now ? new Window(now, old.failures() + 1) : fresh);
		return updated.failures() == threshold;
	}

	/** True once per IP and hour: the aggregated audit row (docs/08 §7). */
	boolean firstTimeThisHour(String ip) {
		if (auditedHour.size() > MAX_TRACKED_IPS) {
			auditedHour.clear();
		}
		long hour = clock.millis() / 3_600_000;
		Long previous = auditedHour.put(ip, hour);
		return previous == null || previous != hour;
	}

	Duration untilNextWindow() {
		return Duration.ofMillis(60_000 - clock.millis() % 60_000);
	}

	private long minute() {
		return clock.millis() / 60_000;
	}

}
