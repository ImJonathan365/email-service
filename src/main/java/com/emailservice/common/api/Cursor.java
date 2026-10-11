package com.emailservice.common.api;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Opaque keyset cursor of docs/07 §1: the (createdAt, id) of the last item returned, so the next
 * page starts strictly after it whatever is inserted meanwhile.
 */
public record Cursor(Instant createdAt, UUID id) {

	public String encode() {
		return Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString((createdAt + "|" + id).getBytes(StandardCharsets.UTF_8));
	}

	/** Null for the first page; a 422 for anything this service did not issue. */
	public static Cursor decode(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|");
			if (parts.length == 2) {
				return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
			}
		}
		catch (IllegalArgumentException | DateTimeParseException ex) {
			// Falls through to the validation error.
		}
		throw ApiException.validation(List.of(new Problem.FieldError("cursor", "is not a cursor returned by this API")));
	}

}
