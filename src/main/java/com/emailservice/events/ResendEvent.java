package com.emailservice.events;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A verified Resend webhook body reduced to what docs/02 §2.1 needs. {@link #minimized()} is the
 * only part that is stored (AC-17.1): an allowlist, so addresses, subject, headers, IPs, user
 * agents and click query strings never reach the database.
 */
record ResendEvent(String providerType, EventType type, Instant occurredAt, Optional<UUID> messageIdTag,
		Optional<String> emailId, List<String> recipients, Optional<String> suppressionEmail, ObjectNode minimized) {

	private static final Pattern PROVIDER_TYPE = Pattern.compile("[a-z_.]{1,60}");

	private static final Pattern PROVIDER_ID = Pattern.compile("[A-Za-z0-9_-]{1,100}");

	/** Raised when a verified body is not a Resend event we can read (answered with 400). */
	static final class Unreadable extends Exception {

		Unreadable(String message) {
			super(message, null, false, false);
		}

	}

	static ResendEvent parse(JsonMapper jsonMapper, byte[] body, Instant fallbackTime) throws Unreadable {
		JsonNode root;
		try {
			root = jsonMapper.readTree(body);
		}
		catch (RuntimeException ex) {
			throw new Unreadable("Body is not JSON");
		}
		if (root == null || !root.isObject()) {
			throw new Unreadable("Body is not a JSON object");
		}
		String rawType = text(root, "type");
		if (rawType == null) {
			throw new Unreadable("Event type missing");
		}
		String providerType = PROVIDER_TYPE.matcher(rawType).matches() ? rawType : "unknown";
		EventType type = EventType.fromResend(providerType);
		Instant occurredAt = instant(text(root, "created_at")).orElse(fallbackTime);
		JsonNode data = root.path("data");

		boolean suppressionEvent = providerType.startsWith("suppression.");
		Optional<String> emailId = Optional
			.ofNullable(text(data, suppressionEvent ? "source_id" : "email_id"))
			.filter(id -> PROVIDER_ID.matcher(id).matches());
		Optional<UUID> messageIdTag = Optional.ofNullable(text(data.path("tags"), "message_id"))
			.flatMap(ResendEvent::uuid);

		List<String> recipients = new ArrayList<>();
		for (JsonNode to : data.path("to")) {
			if (to.isString()) {
				recipients.add(to.stringValue());
			}
		}
		Optional<String> suppressionEmail = suppressionEvent ? Optional.ofNullable(text(data, "email"))
				: Optional.empty();

		return new ResendEvent(providerType, type, occurredAt, messageIdTag, emailId, List.copyOf(recipients),
				suppressionEmail, minimize(jsonMapper, root, data, providerType));
	}

	private static ObjectNode minimize(JsonMapper jsonMapper, JsonNode root, JsonNode data, String providerType) {
		ObjectNode kept = jsonMapper.createObjectNode();
		kept.put("type", providerType);
		copy(root, kept, "created_at");
		ObjectNode keptData = kept.putObject("data");
		copy(data, keptData, "email_id");
		copy(data, keptData, "created_at");
		String tag = text(data.path("tags"), "message_id");
		if (tag != null && uuid(tag).isPresent()) {
			keptData.putObject("tags").put("message_id", tag);
		}
		if (data.path("bounce").isObject()) {
			ObjectNode bounce = keptData.putObject("bounce");
			copy(data.path("bounce"), bounce, "type");
			copy(data.path("bounce"), bounce, "subType");
		}
		if (data.path("suppressed").isObject()) {
			copy(data.path("suppressed"), keptData.putObject("suppressed"), "type");
		}
		if (data.path("click").isObject()) {
			ObjectNode click = keptData.putObject("click");
			copy(data.path("click"), click, "timestamp");
			String link = text(data.path("click"), "link");
			if (link != null) {
				click.put("link", withoutQuery(link));
			}
		}
		if (data.path("open").isObject()) {
			copy(data.path("open"), keptData.putObject("open"), "timestamp");
		}
		if (providerType.startsWith("suppression.")) {
			copy(data, keptData, "id");
			copy(data, keptData, "origin");
			copy(data, keptData, "source_id");
		}
		return kept;
	}

	/** Query strings and fragments carry tokens (reset links): only scheme, host and path are kept. */
	static String withoutQuery(String link) {
		try {
			URI uri = new URI(link);
			return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), null, null).toString();
		}
		catch (URISyntaxException | IllegalArgumentException ex) {
			int cut = link.length();
			for (char separator : new char[] { '?', '#' }) {
				int at = link.indexOf(separator);
				if (at >= 0) {
					cut = Math.min(cut, at);
				}
			}
			return link.substring(0, cut);
		}
	}

	private static void copy(JsonNode from, ObjectNode to, String field) {
		String value = text(from, field);
		if (value != null) {
			to.put(field, value.length() > 200 ? value.substring(0, 200) : value);
		}
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node == null ? null : node.get(field);
		return value != null && value.isString() ? value.stringValue() : null;
	}

	private static Optional<Instant> instant(String value) {
		if (value == null) {
			return Optional.empty();
		}
		try {
			return Optional.of(Instant.parse(value));
		}
		catch (DateTimeParseException ex) {
			return Optional.empty();
		}
	}

	private static Optional<UUID> uuid(String value) {
		try {
			return Optional.of(UUID.fromString(value));
		}
		catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

}
