package com.emailservice.events;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Refused webhooks (AC-16.2): always counted by reason, logged at most once per IP and minute so
 * a flood of forged requests cannot flood the logs. Never logs the body or the signature.
 */
@Component
class WebhookRejections {

	private static final Logger log = LoggerFactory.getLogger(WebhookRejections.class);

	private static final int MAX_TRACKED_IPS = 10_000;

	private final Map<String, Long> lastLoggedMinute = new ConcurrentHashMap<>();

	private final MeterRegistry meters;

	private final Clock clock;

	WebhookRejections(MeterRegistry meters, Clock clock) {
		this.meters = meters;
		this.clock = clock;
	}

	void record(String ip, String reason) {
		meters.counter("email.webhook.rejected", "reason", reason.toLowerCase()).increment();
		if (lastLoggedMinute.size() > MAX_TRACKED_IPS) {
			lastLoggedMinute.clear();
		}
		long minute = clock.millis() / 60_000;
		Long previous = lastLoggedMinute.put(ip, minute);
		if (previous == null || previous != minute) {
			log.warn("Rejected webhook from {} ({}); further rejections from this address are only counted this minute",
					ip, reason);
		}
	}

}
