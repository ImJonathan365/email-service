package com.emailservice.provider.resend;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.emailservice.provider.EmailSender;
import com.emailservice.provider.OutboundEmail;
import com.emailservice.provider.SendException;
import com.emailservice.provider.SendResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Resend over its HTTP API with the JDK client (ADR-0016). Every attempt of a message carries
 * Idempotency-Key = message id, so a retry after a lost response is deduplicated for 24 h
 * (ADR-0010, NFR-04). Responses map to the classes of docs/05 §4; only the provider's error name
 * is kept, never its message, which may echo addresses.
 */
public class ResendEmailSender implements EmailSender {

	public static final String NAME = "resend";

	private static final Pattern ERROR_NAME = Pattern.compile("[a-z_]{1,40}");

	private final HttpClient client;

	private final URI endpoint;

	private final String apiKey;

	private final Duration readTimeout;

	private final JsonMapper jsonMapper;

	public ResendEmailSender(String baseUrl, String apiKey, Duration connectTimeout, Duration readTimeout,
			JsonMapper jsonMapper) {
		this.client = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(connectTimeout)
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
		this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/emails");
		this.apiKey = apiKey;
		this.readTimeout = readTimeout;
		this.jsonMapper = jsonMapper;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public boolean supportsIdempotency() {
		return true;
	}

	@Override
	public SendResult send(OutboundEmail email) throws SendException {
		HttpRequest request = HttpRequest.newBuilder(endpoint)
			.timeout(readTimeout)
			.header("Authorization", "Bearer " + apiKey)
			.header("Content-Type", "application/json")
			.header("Idempotency-Key", email.messageId().toString())
			.header("User-Agent", "email-service")
			.POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body(email))))
			.build();
		HttpResponse<String> response;
		try {
			response = client.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch (HttpTimeoutException ex) {
			throw SendException.transientFailure("PROVIDER_TIMEOUT", "Resend did not answer in time.", ex);
		}
		catch (IOException ex) {
			// The request may have been accepted: the retry carries the same key, so Resend deduplicates.
			throw SendException.transientFailure("PROVIDER_UNAVAILABLE", "The connection to Resend failed.", ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw SendException.transientFailure("PROVIDER_UNAVAILABLE", "Interrupted while calling Resend.", ex);
		}
		return classify(response);
	}

	/** Fixed field order: the same message always produces the same body (AC-12.7). */
	Map<String, Object> body(OutboundEmail email) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("from", address(email.from()));
		body.put("to", List.of(address(email.to())));
		if (!email.cc().isEmpty()) {
			body.put("cc", email.cc());
		}
		if (!email.bcc().isEmpty()) {
			body.put("bcc", email.bcc());
		}
		if (email.replyTo() != null) {
			body.put("reply_to", email.replyTo().email());
		}
		body.put("subject", email.subject());
		body.put("html", email.html());
		if (email.text() != null) {
			body.put("text", email.text());
		}
		List<Map<String, String>> tags = new ArrayList<>();
		tags.add(Map.of("name", "message_id", "value", email.messageId().toString()));
		for (String tag : email.tags()) {
			tags.add(Map.of("name", tag, "value", "true"));
		}
		body.put("tags", tags);
		return body;
	}

	private SendResult classify(HttpResponse<String> response) throws SendException {
		int status = response.statusCode();
		if (status >= 200 && status < 300) {
			JsonNode id = parse(response.body()).get("id");
			if (id == null || !id.isString() || id.stringValue().isBlank()) {
				// Possibly accepted: retrying with the same key returns the id without a second email.
				throw SendException.transientFailure("PROVIDER_UNAVAILABLE", "Resend answered without an email id.", null);
			}
			return new SendResult(NAME, id.stringValue());
		}
		String error = errorName(response.body());
		String detail = "Resend " + status + " " + error;
		throw switch (status) {
			case 401, 404, 405 -> configuration(error, detail);
			// Resend's 403 validation_error on send means the sender domain is not verified.
			case 403 -> "validation_error".equals(error)
					? new SendException(SendException.Kind.TENANT_CONFIGURATION, "PROVIDER_DOMAIN_NOT_VERIFIED", detail,
							null, error, null)
					: configuration(error, detail);
			// Only a reused key with a different body is final; concurrent_idempotent_requests and
			// resource_locked mean the same key is still in flight.
			case 409 -> "invalid_idempotent_request".equals(error)
					? new SendException(SendException.Kind.PERMANENT, "PROVIDER_IDEMPOTENCY_CONFLICT", detail, null, error,
							null)
					: new SendException(SendException.Kind.TRANSIENT, "PROVIDER_UNAVAILABLE", detail, null, error, null);
			case 429 -> new SendException(SendException.Kind.TRANSIENT, "PROVIDER_RATE_LIMITED", detail,
					retryAfter(response), error, null);
			default -> status >= 500
					? new SendException(SendException.Kind.TRANSIENT, "PROVIDER_UNAVAILABLE", detail, null, error, null)
					: new SendException(SendException.Kind.PERMANENT, "PROVIDER_REJECTED", detail, null, error, null);
		};
	}

	private static SendException configuration(String error, String detail) {
		return new SendException(SendException.Kind.CONFIGURATION, "PROVIDER_CONFIGURATION", detail, null, error, null);
	}

	private String errorName(String body) {
		JsonNode name = parse(body).get("name");
		String value = name != null && name.isString() ? name.stringValue() : "";
		return ERROR_NAME.matcher(value).matches() ? value : "unknown";
	}

	private JsonNode parse(String body) {
		try {
			return body == null || body.isBlank() ? jsonMapper.createObjectNode() : jsonMapper.readTree(body);
		}
		catch (RuntimeException ex) {
			return jsonMapper.createObjectNode();
		}
	}

	/** Retry-After as delta seconds or an HTTP date (RFC 9110). */
	static Duration retryAfter(HttpResponse<?> response) {
		String value = response.headers().firstValue("Retry-After").orElse(null);
		if (value == null) {
			return null;
		}
		try {
			return Duration.ofSeconds(Math.max(0, Long.parseLong(value.trim())));
		}
		catch (NumberFormatException notSeconds) {
			try {
				Duration until = Duration.between(ZonedDateTime.now(),
						ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME));
				return until.isNegative() ? Duration.ZERO : until;
			}
			catch (DateTimeParseException ex) {
				return null;
			}
		}
	}

	private static String address(OutboundEmail.Address address) {
		if (address.name() == null || address.name().isBlank()) {
			return address.email();
		}
		String quoted = address.name().replace("\\", "\\\\").replace("\"", "\\\"");
		return "\"" + quoted + "\" <" + address.email() + ">";
	}

	@Override
	public String toString() {
		return "ResendEmailSender[endpoint=" + endpoint + ", apiKey=***]";
	}

}
