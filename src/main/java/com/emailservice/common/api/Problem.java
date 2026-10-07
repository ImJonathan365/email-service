package com.emailservice.common.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** RFC 9457 problem body with the service's {@code code} and {@code requestId} (docs/07 §11). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Problem(String type, String title, int status, String detail, String instance, String code,
		String requestId, List<FieldError> errors) {

	public static final String MEDIA_TYPE = "application/problem+json";

	public static Problem of(ErrorCode code, String detail, String instance, String requestId,
			List<FieldError> errors) {
		return new Problem(code.type(), code.title(), code.status(), detail, instance, code.name(), requestId,
				errors == null || errors.isEmpty() ? null : errors);
	}

	public record FieldError(String field, String message) {
	}

}
