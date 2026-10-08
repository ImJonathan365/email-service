package com.emailservice.sending;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.emailservice.common.config.RoleConditions.ConditionalOnApiRole;
import com.emailservice.tenancy.AuthenticatedApiKey;
import com.emailservice.tenancy.RequiresScope;
import com.emailservice.tenancy.Scope;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/** POST /v1/emails (FR-07): accepts and enqueues; the worker sends. */
@ConditionalOnApiRole
@RestController
@RequestMapping("/v1/emails")
@Tag(name = "Emails")
@SecurityRequirement(name = "apiKey")
class EmailController {

	private final AcceptanceService acceptance;

	EmailController(AcceptanceService acceptance) {
		this.acceptance = acceptance;
	}

	@PostMapping
	@RequiresScope(Scope.EMAILS_SEND)
	@Operation(summary = "Send a transactional email: 202 when queued, 200 for an idempotent repeat (FR-07, FR-08)")
	ResponseEntity<EmailAccepted> send(@Valid @RequestBody SendEmailRequest body,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
			HttpServletRequest request) {
		AcceptanceService.Outcome outcome = acceptance.accept(AuthenticatedApiKey.from(request).tenantId(), body,
				idempotencyKey);
		MDC.put("messageId", outcome.body().id().toString());
		return ResponseEntity.status(outcome.created() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(outcome.body());
	}

}
