package com.emailservice.common.api;

import java.util.Locale;

/** Closed catalog of API error codes (docs/07 §11). Adding one is a contract change. */
public enum ErrorCode {

	MALFORMED_REQUEST(400, "Malformed request"),
	UNAUTHENTICATED(401, "Authentication required"),
	ADMIN_REQUIRED(403, "Administrator credential required"),
	INSUFFICIENT_SCOPE(403, "Insufficient scope"),
	IP_NOT_ALLOWED(403, "Source IP not allowed"),
	TENANT_SUSPENDED(403, "Tenant suspended"),
	TENANT_SENDING_PAUSED(403, "Tenant sending paused"),
	FROM_DOMAIN_NOT_ALLOWED(403, "Sender domain not allowed"),
	RECIPIENT_NOT_ALLOWED_IN_ENV(403, "Recipient not allowed in this environment"),
	TEMPLATE_NOT_FOUND(404, "Template not found"),
	MESSAGE_NOT_FOUND(404, "Message not found"),
	RESOURCE_NOT_FOUND(404, "Resource not found"),
	IDEMPOTENCY_KEY_REUSED(409, "Idempotency key reused with a different request"),
	TEMPLATE_KEY_TAKEN(409, "Template key already in use"),
	TENANT_SLUG_TAKEN(409, "Tenant slug already in use"),
	VERSION_IMMUTABLE(409, "Published version is immutable"),
	MESSAGE_NOT_CANCELABLE(409, "Message is no longer queued"),
	PAYLOAD_TOO_LARGE(413, "Payload too large"),
	VALIDATION_ERROR(422, "Validation failed"),
	INVALID_EMAIL_ADDRESS(422, "Invalid email address"),
	HEADER_INJECTION_DETECTED(422, "Header injection detected"),
	TEMPLATE_VARIABLES_INVALID(422, "Template variables invalid"),
	TEMPLATE_NOT_PUBLISHED(422, "Template has no published version"),
	TEMPLATE_SYNTAX_ERROR(422, "Template syntax error"),
	UNSAFE_TEMPLATE_CONSTRUCT(422, "Unsafe template construct"),
	UNSAFE_URL(422, "Unsafe URL"),
	SEND_AT_OUT_OF_RANGE(422, "sendAt out of range"),
	RECIPIENT_SUPPRESSED(422, "Recipient suppressed"),
	RATE_LIMITED(429, "Rate limit exceeded"),
	INTERNAL_ERROR(500, "Internal error"),
	SERVICE_UNAVAILABLE(503, "Service unavailable");

	private static final String TYPE_BASE = "https://email-service.internal/problems/";

	private final int status;

	private final String title;

	ErrorCode(int status, String title) {
		this.status = status;
		this.title = title;
	}

	public int status() {
		return status;
	}

	public String title() {
		return title;
	}

	public String type() {
		return TYPE_BASE + name().toLowerCase(Locale.ROOT).replace('_', '-');
	}

}
