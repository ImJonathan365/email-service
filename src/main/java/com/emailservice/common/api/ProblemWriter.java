package com.emailservice.common.api;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/** Renders problems both from controllers and from servlet filters, which run outside Spring MVC. */
@Component
public class ProblemWriter {

	private final JsonMapper jsonMapper;

	public ProblemWriter(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	public Problem problem(HttpServletRequest request, ErrorCode code, String detail,
			List<Problem.FieldError> errors) {
		return Problem.of(code, detail, request.getRequestURI(), RequestIdFilter.current(request), errors);
	}

	public ResponseEntity<Problem> entity(HttpServletRequest request, ErrorCode code, String detail,
			List<Problem.FieldError> errors) {
		return ResponseEntity.status(code.status())
			.header("Content-Type", Problem.MEDIA_TYPE)
			.body(problem(request, code, detail, errors));
	}

	public void write(HttpServletRequest request, HttpServletResponse response, ApiException exception)
			throws IOException {
		response.setStatus(exception.code().status());
		response.setContentType(Problem.MEDIA_TYPE);
		response.setCharacterEncoding("UTF-8");
		jsonMapper.writeValue(response.getOutputStream(),
				problem(request, exception.code(), exception.getMessage(), exception.errors()));
	}

}
