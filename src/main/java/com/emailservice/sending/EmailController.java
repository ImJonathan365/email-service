package com.emailservice.sending;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.emailservice.common.api.ApiException;
import com.emailservice.common.api.Cursor;
import com.emailservice.common.api.ErrorCode;
import com.emailservice.common.api.ListResponse;
import com.emailservice.common.config.RoleConditions.ConditionalOnApiRole;
import com.emailservice.tenancy.AuthenticatedApiKey;
import com.emailservice.tenancy.RequiresScope;
import com.emailservice.tenancy.Scope;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/** POST /v1/emails (FR-07) accepts and enqueues, the worker sends; GET reads state and history (FR-19, FR-20). */
@ConditionalOnApiRole
@RestController
@RequestMapping("/v1/emails")
@Tag(name = "Emails")
@SecurityRequirement(name = "apiKey")
class EmailController {

	private final AcceptanceService acceptance;

	private final EmailQueries queries;

	private final MeterRegistry meters;

	private final Cancellation cancellation;

	EmailController(AcceptanceService acceptance, EmailQueries queries, MeterRegistry meters,
			Cancellation cancellation) {
		this.acceptance = acceptance;
		this.queries = queries;
		this.meters = meters;
		this.cancellation = cancellation;
	}

	@PostMapping
	@RequiresScope(Scope.EMAILS_SEND)
	@Operation(summary = "Send a transactional email: 202 when queued, 200 for an idempotent repeat (FR-07, FR-08)")
	ResponseEntity<EmailAccepted> send(@Valid @RequestBody SendEmailRequest body,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
			HttpServletRequest request) {
		Timer.Sample accepting = Timer.start(meters);
		AcceptanceService.Outcome outcome = acceptance.accept(AuthenticatedApiKey.from(request).tenantId(), body,
				idempotencyKey);
		accepting.stop(Timer.builder("email.acceptance")
			.description("Time to validate and enqueue one message (AC-24.2)")
			.tag("outcome", outcome.created() ? "created" : "repeat")
			.register(meters));
		MDC.put("messageId", outcome.body().id().toString());
		return ResponseEntity.status(outcome.created() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(outcome.body());
	}

	@PostMapping("/{id}/cancel")
	@RequiresScope(Scope.EMAILS_SEND)
	@Transactional
	@Operation(summary = "Cancel a message that is still QUEUED, including a scheduled one (FR-26, AC-31.3)")
	EmailViews.Detail cancel(@PathVariable UUID id, HttpServletRequest request) {
		UUID tenantId = AuthenticatedApiKey.from(request).tenantId();
		cancellation.cancel(tenantId, id);
		return queries.detail(tenantId, id).orElseThrow();
	}

	@GetMapping("/{id}")
	@RequiresScope(Scope.EMAILS_READ)
	@Transactional(readOnly = true)
	@Operation(summary = "State, attempts and event history of one message (FR-19)")
	EmailViews.Detail get(@PathVariable UUID id, HttpServletRequest request) {
		return queries.detail(AuthenticatedApiKey.from(request).tenantId(), id)
			.orElseThrow(() -> new ApiException(ErrorCode.MESSAGE_NOT_FOUND, "No message with that id."));
	}

	@GetMapping
	@RequiresScope(Scope.EMAILS_READ)
	@Transactional(readOnly = true)
	@Operation(summary = "List messages, newest first, with filters and a cursor (FR-20)")
	ListResponse<EmailViews.Summary> list(
			@RequestParam(required = false) @Pattern(regexp = EmailViews.STATUSES) String status,
			@RequestParam(required = false) @Size(max = 100) String templateKey,
			@RequestParam(required = false) @Size(max = 254) String to,
			@RequestParam(required = false) @Size(max = 100) String tag,
			@RequestParam(required = false) Instant createdAfter, @RequestParam(required = false) Instant createdBefore,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
			@RequestParam(required = false) String cursor, HttpServletRequest request) {
		EmailViews.Filter filter = new EmailViews.Filter(status, templateKey, to, tag, createdAfter, createdBefore);
		List<EmailViews.Summary> rows = queries.page(AuthenticatedApiKey.from(request).tenantId(), filter,
				Cursor.decode(cursor), limit + 1);
		List<EmailViews.Summary> page = rows.subList(0, Math.min(limit, rows.size()));
		String next = rows.size() > limit ? new Cursor(page.getLast().createdAt(), page.getLast().id()).encode() : null;
		return new ListResponse<>(page, next);
	}

}
