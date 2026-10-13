package com.emailservice.sending;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.JsonNode;

/** Bodies of GET /v1/emails/{id} and GET /v1/emails (docs/07 §5 and §6). */
final class EmailViews {

	static final String STATUSES = "QUEUED|SENDING|SENT|DELIVERED|BOUNCED|COMPLAINED|FAILED|CANCELED";

	private EmailViews() {
	}

	/**
	 * The full state of one message (AC-19.1). lastError is the code of the latest attempt, also
	 * while it is still retrying; rendered* only when the tenant keeps rendered content (AC-19.3).
	 */
	record Detail(UUID id, String status, String category, String templateKey, int templateVersion, String locale,
			String to, List<String> cc, List<String> bcc, String from, List<String> tags, JsonNode metadata,
			int attempts, String provider, String providerMessageId, String lastError, String failureCode,
			String failureDetail, Instant createdAt, Instant sentAt, Instant deliveredAt, Instant firstOpenedAt,
			Instant firstClickedAt, int openCount, int clickCount,
			@JsonInclude(JsonInclude.Include.NON_NULL) String renderedSubject,
			@JsonInclude(JsonInclude.Include.NON_NULL) String renderedHtml,
			@JsonInclude(JsonInclude.Include.NON_NULL) String renderedText, List<Event> events) {
	}

	record Event(String type, Instant occurredAt) {
	}

	record Summary(UUID id, String status, String to, String templateKey, Instant createdAt) {
	}

	/** The filters of AC-20.1; null means not filtered. */
	record Filter(String status, String templateKey, String to, String tag, Instant createdAfter,
			Instant createdBefore) {
	}

}
