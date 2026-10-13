package com.emailservice.common.api;

import java.time.Duration;
import java.util.List;

/** An error the client caused or must react to; rendered as problem+json with its catalog code. */
public class ApiException extends RuntimeException {

	private final ErrorCode code;

	private final List<Problem.FieldError> errors;

	private final Duration retryAfter;

	public ApiException(ErrorCode code, String detail) {
		this(code, detail, List.of());
	}

	public ApiException(ErrorCode code, String detail, List<Problem.FieldError> errors) {
		this(code, detail, errors, null);
	}

	private ApiException(ErrorCode code, String detail, List<Problem.FieldError> errors, Duration retryAfter) {
		super(detail, null, false, false);
		this.code = code;
		this.errors = List.copyOf(errors);
		this.retryAfter = retryAfter;
	}

	/** A 429 with Retry-After in whole seconds, never less than one (AC-21.1). */
	public static ApiException rateLimited(String detail, Duration retryAfter) {
		return new ApiException(ErrorCode.RATE_LIMITED, detail, List.of(), retryAfter);
	}

	/** Seconds for the Retry-After header, or null when the response has none. */
	public Long retryAfterSeconds() {
		return retryAfter == null ? null : Math.max(1, (retryAfter.toMillis() + 999) / 1000);
	}

	public static ApiException validation(List<Problem.FieldError> errors) {
		return new ApiException(ErrorCode.VALIDATION_ERROR, "One or more fields are invalid.", errors);
	}

	public ErrorCode code() {
		return code;
	}

	public List<Problem.FieldError> errors() {
		return errors;
	}

}
