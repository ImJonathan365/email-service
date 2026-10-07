package com.emailservice.common.api;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps every controller failure to a catalog code (NFR-17); no internal detail leaks to clients. */
@RestControllerAdvice
class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	private final ProblemWriter problems;

	ApiExceptionHandler(ProblemWriter problems) {
		this.problems = problems;
	}

	@ExceptionHandler(ApiException.class)
	ResponseEntity<Problem> api(ApiException ex, HttpServletRequest request) {
		return problems.entity(request, ex.code(), ex.getMessage(), ex.errors());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<Problem> invalidBody(MethodArgumentNotValidException ex, HttpServletRequest request) {
		List<Problem.FieldError> errors = ex.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(error -> new Problem.FieldError(error.getField(), error.getDefaultMessage()))
			.toList();
		return problems.entity(request, ErrorCode.VALIDATION_ERROR, "One or more fields are invalid.", errors);
	}

	@ExceptionHandler(HandlerMethodValidationException.class)
	ResponseEntity<Problem> invalidParameters(HandlerMethodValidationException ex, HttpServletRequest request) {
		List<Problem.FieldError> errors = ex.getParameterValidationResults()
			.stream()
			.flatMap(result -> result.getResolvableErrors()
				.stream()
				.map(error -> new Problem.FieldError(result.getMethodParameter().getParameterName(),
						error.getDefaultMessage())))
			.toList();
		return problems.entity(request, ErrorCode.VALIDATION_ERROR, "One or more fields are invalid.", errors);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<Problem> malformed(HttpMessageNotReadableException ex, HttpServletRequest request) {
		return problems.entity(request, ErrorCode.MALFORMED_REQUEST, "The request body could not be read as JSON.",
				List.of());
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	ResponseEntity<Problem> unsupportedMediaType(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
		return problems.entity(request, ErrorCode.UNSUPPORTED_MEDIA_TYPE, "The request body must be application/json.",
				List.of());
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	ResponseEntity<Problem> methodNotAllowed(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
		Set<HttpMethod> supported = ex.getSupportedHttpMethods();
		String allow = supported == null ? ""
				: supported.stream().map(HttpMethod::name).sorted().collect(Collectors.joining(", "));
		return ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.status())
			.header("Content-Type", Problem.MEDIA_TYPE)
			.header("Allow", allow)
			.body(problems.problem(request, ErrorCode.METHOD_NOT_ALLOWED,
					"Method " + ex.getMethod() + " is not supported on this resource.", List.of()));
	}

	@ExceptionHandler({ NoResourceFoundException.class, MethodArgumentTypeMismatchException.class })
	ResponseEntity<Problem> notFound(Exception ex, HttpServletRequest request) {
		return problems.entity(request, ErrorCode.RESOURCE_NOT_FOUND, "The requested resource does not exist.",
				List.of());
	}

	@ExceptionHandler({ CannotGetJdbcConnectionException.class, CannotCreateTransactionException.class,
			DataAccessResourceFailureException.class })
	ResponseEntity<Problem> databaseUnavailable(Exception ex, HttpServletRequest request) {
		log.error("Database unavailable", ex);
		return problems.entity(request, ErrorCode.SERVICE_UNAVAILABLE, "The service is temporarily unavailable.",
				List.of());
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<Problem> unexpected(Exception ex, HttpServletRequest request) {
		log.error("Unexpected error", ex);
		return problems.entity(request, ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.", List.of());
	}

}
