package com.emailservice.events;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.emailservice.common.api.ApiException;
import com.emailservice.common.api.ErrorCode;
import com.emailservice.common.config.RoleConditions.ConditionalOnApiRole;
import com.emailservice.common.web.ClientIpResolver;
import com.emailservice.provider.WebhookVerifier;

import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import tools.jackson.databind.json.JsonMapper;

/**
 * POST /webhooks/{provider} (FR-16). Public and without an API key: the provider's signature over
 * the raw body is the authentication, checked before the body is parsed. Never a 5xx for an event
 * that cannot be correlated; 503 only when the database fails, so the provider retries (AC-16.7).
 */
@ConditionalOnApiRole
@RestController
@Tag(name = "Provider webhooks")
class WebhookController {

	private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

	private final Map<String, WebhookVerifier> verifiers;

	private final EventIngestion ingestion;

	private final WebhookRejections rejections;

	private final ClientIpResolver clientIpResolver;

	private final JsonMapper jsonMapper;

	private final MeterRegistry meters;

	WebhookController(List<WebhookVerifier> verifiers, EventIngestion ingestion, WebhookRejections rejections,
			ClientIpResolver clientIpResolver, JsonMapper jsonMapper, MeterRegistry meters) {
		this.verifiers = verifiers.stream()
			.collect(Collectors.toUnmodifiableMap(WebhookVerifier::provider, Function.identity()));
		this.ingestion = ingestion;
		this.rejections = rejections;
		this.clientIpResolver = clientIpResolver;
		this.jsonMapper = jsonMapper;
		this.meters = meters;
	}

	@PostMapping("/webhooks/{provider}")
	@Operation(summary = "Provider events, authenticated by the provider's signature (FR-16, FR-17)",
			parameters = { @Parameter(name = "svix-id", in = ParameterIn.HEADER, required = true),
					@Parameter(name = "svix-timestamp", in = ParameterIn.HEADER, required = true),
					@Parameter(name = "svix-signature", in = ParameterIn.HEADER, required = true) },
			responses = { @ApiResponse(responseCode = "204", description = "Processed, duplicate or orphan"),
					@ApiResponse(responseCode = "400", description = "Authentic but unreadable body"),
					@ApiResponse(responseCode = "401", description = "Invalid or missing signature, or outside the window"),
					@ApiResponse(responseCode = "503", description = "Database unavailable; the provider retries") })
	ResponseEntity<Void> receive(@PathVariable String provider, @RequestBody(required = false) byte[] body,
			HttpServletRequest request) {
		WebhookVerifier verifier = verifiers.get(provider);
		if (verifier == null) {
			throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "The requested resource does not exist.");
		}
		byte[] raw = body == null ? new byte[0] : body;

		WebhookVerifier.Verified verified;
		try {
			verified = verifier.verify(request::getHeader, raw);
		}
		catch (WebhookVerifier.Rejected ex) {
			rejections.record(clientIpResolver.resolve(request).getHostAddress(), ex.reason().name());
			throw new ApiException(ErrorCode.UNAUTHENTICATED, "The webhook signature could not be verified.");
		}

		ResendEvent event;
		try {
			event = ResendEvent.parse(jsonMapper, raw, Instant.now());
		}
		catch (ResendEvent.Unreadable ex) {
			meters.counter("email.webhook.rejected", "reason", "unreadable").increment();
			throw new ApiException(ErrorCode.MALFORMED_REQUEST, "The webhook body could not be read.");
		}

		try {
			ingestion.ingest(provider, verified, event);
		}
		catch (DataAccessException | TransactionException ex) {
			log.error("Database error while storing webhook event {}; the provider will retry", verified.eventId(), ex);
			throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "The service is temporarily unavailable.");
		}
		return ResponseEntity.noContent().build();
	}

}
