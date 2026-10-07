package com.emailservice.common.api;

import java.util.List;

/** An error the client caused or must react to; rendered as problem+json with its catalog code. */
public class ApiException extends RuntimeException {

	private final ErrorCode code;

	private final List<Problem.FieldError> errors;

	public ApiException(ErrorCode code, String detail) {
		this(code, detail, List.of());
	}

	public ApiException(ErrorCode code, String detail, List<Problem.FieldError> errors) {
		super(detail, null, false, false);
		this.code = code;
		this.errors = List.copyOf(errors);
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
