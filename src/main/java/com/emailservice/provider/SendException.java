package com.emailservice.provider;

import java.time.Duration;

/**
 * A failed send, already classified (docs/05 §4). {@code code} is a failure_code of docs/06 §4;
 * {@code detail} is short and free of personal data, since it is stored in failure_detail.
 */
public class SendException extends Exception {

	public enum Kind {

		/** Retry with backoff: timeouts, network errors, 429, 5xx. */
		TRANSIENT,
		/** Never retry: invalid recipient, rejected content, other 4xx. */
		PERMANENT

	}

	private final Kind kind;

	private final String code;

	private final Duration retryAfter;

	public SendException(Kind kind, String code, String detail, Duration retryAfter, Throwable cause) {
		super(detail, cause);
		this.kind = kind;
		this.code = code;
		this.retryAfter = retryAfter;
	}

	public static SendException transientFailure(String code, String detail, Throwable cause) {
		return new SendException(Kind.TRANSIENT, code, detail, null, cause);
	}

	public static SendException permanentFailure(String code, String detail, Throwable cause) {
		return new SendException(Kind.PERMANENT, code, detail, null, cause);
	}

	public Kind kind() {
		return kind;
	}

	public String code() {
		return code;
	}

	/** A provider-imposed wait (429 Retry-After) that replaces the computed backoff (AC-13.5). */
	public Duration retryAfter() {
		return retryAfter;
	}

}
