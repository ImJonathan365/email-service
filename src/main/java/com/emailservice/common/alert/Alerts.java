package com.emailservice.common.alert;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Alerts of NFR-11 as structured ERROR log lines (owner, 2026-10-12): a log-based alert in the
 * hosting matches the "alert" field. The same alert for the same subject is emitted at most once
 * every 15 minutes per instance. Subjects are slugs or names, never addresses or secrets.
 */
@Component
public class Alerts {

	private static final Logger log = LoggerFactory.getLogger("com.emailservice.alert");

	static final Duration DEDUPLICATION = Duration.ofMinutes(15);

	private final Map<String, Long> lastEmitted = new ConcurrentHashMap<>();

	private final Clock clock;

	public Alerts(Clock clock) {
		this.clock = clock;
	}

	/** Returns whether the alert was emitted (false while deduplicated). */
	public boolean raise(String alert, String subject, String message) {
		long now = clock.millis();
		String key = alert + "|" + subject;
		Long previous = lastEmitted.get(key);
		// A clock that went backwards must not silence alerts until it catches up.
		if (previous != null && now >= previous && now - previous < DEDUPLICATION.toMillis()) {
			return false;
		}
		lastEmitted.put(key, now);
		log.atError().addKeyValue("alert", alert).addKeyValue("alertSubject", subject).log(message);
		return true;
	}

}
