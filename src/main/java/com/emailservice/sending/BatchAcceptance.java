package com.emailservice.sending;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.emailservice.common.api.ApiException;
import com.emailservice.common.api.ErrorCode;
import com.emailservice.common.api.Problem.FieldError;
import com.emailservice.common.config.AppProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * POST /v1/emails/batch (FR-35): every item goes through the same acceptance as POST /v1/emails,
 * inside one tenant transaction and its own savepoint. A refused item rolls back only itself,
 * its quota increment included; a database failure rolls the whole batch back (AC-35.3). Once
 * the rate limit runs out, the remaining items are only answered: an idempotent repeat with its
 * original result (AC-08.7, owner H8), anything else with 429.
 */
@Service
class BatchAcceptance {

	private final AcceptanceService acceptance;

	private final MessageRepository messages;

	private final Validator validator;

	private final JsonMapper jsonMapper;

	private final TransactionTemplate savepoint;

	private final int maxItems;

	BatchAcceptance(AcceptanceService acceptance, MessageRepository messages, Validator validator, JsonMapper jsonMapper,
			PlatformTransactionManager transactionManager, AppProperties properties) {
		this.acceptance = acceptance;
		this.messages = messages;
		this.validator = validator;
		this.jsonMapper = jsonMapper;
		this.savepoint = new TransactionTemplate(transactionManager);
		this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
		this.maxItems = properties.sending().maxBatchItems();
	}

	@Transactional
	BatchViews.Response accept(UUID tenantId, BatchViews.Request request) {
		List<JsonNode> items = request == null || request.items() == null ? List.of() : request.items();
		if (items.isEmpty() || items.size() > maxItems) {
			throw ApiException.validation(List.of(new FieldError("items", "must hold 1 to " + maxItems + " items")));
		}
		// A paused tenant gets no item processed at all (AC-34.4).
		if (messages.tenant(tenantId).paused()) {
			throw new ApiException(ErrorCode.TENANT_SENDING_PAUSED, "Sending is paused for this tenant.");
		}

		List<BatchViews.Result> results = new ArrayList<>();
		boolean rateLimited = false;
		for (int index = 0; index < items.size(); index++) {
			Item item;
			try {
				item = read(items.get(index));
			}
			catch (ApiException ex) {
				results.add(BatchViews.Result.error(index, ex.code().status(), ex.code().name()));
				continue;
			}
			if (rateLimited) {
				results.add(afterTheLimit(index, tenantId, item));
				continue;
			}
			BatchViews.Result result = acceptInSavepoint(index, tenantId, item);
			rateLimited = result.httpStatus() == ErrorCode.RATE_LIMITED.status();
			results.add(result);
		}
		return new BatchViews.Response(results);
	}

	private record Item(SendEmailRequest request, String idempotencyKey) {
	}

	private BatchViews.Result acceptInSavepoint(int index, UUID tenantId, Item item) {
		try {
			AcceptanceService.Outcome outcome = savepoint
				.execute(status -> acceptance.accept(tenantId, item.request(), item.idempotencyKey()));
			return BatchViews.Result.of(index, outcome);
		}
		catch (ApiException ex) {
			return BatchViews.Result.error(index, ex.code().status(), ex.code().name());
		}
	}

	private BatchViews.Result afterTheLimit(int index, UUID tenantId, Item item) {
		try {
			Optional<AcceptanceService.Outcome> repeat = acceptance.repeatOnly(tenantId, item.request(),
					item.idempotencyKey());
			return repeat.map(outcome -> BatchViews.Result.of(index, outcome))
				.orElseGet(() -> BatchViews.Result.error(index, ErrorCode.RATE_LIMITED.status(),
						ErrorCode.RATE_LIMITED.name()));
		}
		catch (ApiException ex) {
			return BatchViews.Result.error(index, ex.code().status(), ex.code().name());
		}
	}

	/** Reads one item as a POST /v1/emails body with its required idempotencyKey. */
	private Item read(JsonNode node) {
		if (node == null || !node.isObject()) {
			throw ApiException.validation(List.of(new FieldError("item", "must be a JSON object")));
		}
		JsonNode key = node.get("idempotencyKey");
		if (key == null || !key.isString() || key.stringValue().isBlank()) {
			throw ApiException.validation(List.of(new FieldError("idempotencyKey", "is required in a batch item")));
		}
		ObjectNode body = ((ObjectNode) node).deepCopy();
		body.remove("idempotencyKey");
		SendEmailRequest request;
		try {
			request = jsonMapper.treeToValue(body, SendEmailRequest.class);
		}
		catch (RuntimeException ex) {
			throw new ApiException(ErrorCode.MALFORMED_REQUEST, "The item could not be read.");
		}
		Set<ConstraintViolation<SendEmailRequest>> violations = validator.validate(request);
		if (!violations.isEmpty()) {
			throw ApiException.validation(violations.stream()
				.map(violation -> new FieldError(violation.getPropertyPath().toString(), violation.getMessage()))
				.toList());
		}
		return new Item(request, key.stringValue());
	}

}
