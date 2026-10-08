package com.emailservice.sending;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response of POST /v1/emails: 202 when created, 200 with the same object for an idempotent
 * repeat (AC-08.1). failureCode and suppressionReason only appear for a suppressed recipient.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EmailAccepted(UUID id, String status, String templateKey, int templateVersion, String locale, String to,
		List<String> droppedRecipients, String failureCode, String suppressionReason, Instant createdAt) {
}
