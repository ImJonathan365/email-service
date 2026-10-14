package com.emailservice.provider;

import java.time.Instant;
import java.util.function.Function;

/**
 * Authenticates an incoming provider webhook (FR-16, AC-14.7) over the raw body, before anything
 * parses it. One implementation per provider; the events module only sees this port.
 */
public interface WebhookVerifier {

	/** The {provider} path segment this verifier answers for, e.g. "resend". */
	String provider();

	/**
	 * @param header reads a request header by name (null when absent)
	 * @param body the body exactly as received
	 * @throws Rejected when the request is not authentic or outside the replay window
	 */
	Verified verify(Function<String, String> header, byte[] body) throws Rejected;

	/** An authentic delivery: the provider's unique event id (dedupe key) and its signing time. */
	record Verified(String eventId, Instant signedAt) {
	}

	/** Why a webhook was refused; never carries the received signature or body. */
	final class Rejected extends Exception {

		public enum Reason {

			MISSING_HEADERS, INVALID_SIGNATURE, OUTSIDE_TOLERANCE

		}

		private final Reason reason;

		public Rejected(Reason reason) {
			super("Webhook rejected: " + reason, null, false, false);
			this.reason = reason;
		}

		public Reason reason() {
			return reason;
		}

	}

}
