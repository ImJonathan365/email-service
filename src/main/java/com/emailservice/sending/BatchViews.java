package com.emailservice.sending;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

/** Bodies of POST /v1/emails/batch (FR-35, docs/07 §4). */
final class BatchViews {

	private BatchViews() {
	}

	/**
	 * Items stay raw JSON until each is read on its own: one malformed item must not fail the
	 * others (AC-35.2). Each has the fields of POST /v1/emails plus a required idempotencyKey.
	 */
	record Request(@Schema(description = "Up to MAX_BATCH_ITEMS items, each a POST /v1/emails body plus "
			+ "a required idempotencyKey") List<JsonNode> items) {
	}

	/** One result per item, in the request's order. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Result(int index, int httpStatus, UUID id, String status, String code, String failureCode,
			String suppressionReason, List<String> droppedRecipients) {

		static Result of(int index, AcceptanceService.Outcome outcome) {
			EmailAccepted body = outcome.body();
			return new Result(index, outcome.created() ? 202 : 200, body.id(), body.status(), null, body.failureCode(),
					body.suppressionReason(),
					body.droppedRecipients() == null || body.droppedRecipients().isEmpty() ? null
							: body.droppedRecipients());
		}

		static Result error(int index, int httpStatus, String code) {
			return new Result(index, httpStatus, null, null, code, null, null, null);
		}

	}

	record Response(List<Result> results) {
	}

}
