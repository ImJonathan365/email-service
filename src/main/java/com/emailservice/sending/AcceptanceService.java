package com.emailservice.sending;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.emailservice.common.Uuids;
import com.emailservice.common.api.ApiException;
import com.emailservice.common.api.ErrorCode;
import com.emailservice.common.api.Problem.FieldError;
import com.emailservice.common.config.AppProperties;
import com.emailservice.templates.TemplateResolver;
import com.emailservice.templates.engine.TemplateEngine;
import com.emailservice.templates.schema.UrlPolicy;
import com.emailservice.templates.schema.VariablesValidator;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Accepts a send request in one tenant transaction (docs/05 §3, FR-07 to FR-11): idempotency
 * first, then validation, version pinning, variables, URL rule, a discarded render, suppression,
 * the rate limit hook and the INSERT that is also the queue entry. Never contacts the provider.
 */
@Service
class AcceptanceService {

	static final int MAX_IDEMPOTENCY_KEY = 256;

	static final int MAX_METADATA_BYTES = 4_096;

	private static final Pattern PRINTABLE_ASCII = Pattern.compile("[\\x20-\\x7E]{1,256}");

	private static final Pattern TAG = Pattern.compile("[a-z0-9_-]{1,64}");

	private final MessageRepository messages;

	private final TemplateResolver resolver;

	private final TemplateEngine engine;

	private final SuppressionHashes hashes;

	private final AcceptanceRateLimiter rateLimiter;

	private final AppProperties properties;

	private final JsonMapper jsonMapper;

	private final JsonMapper canonicalMapper;

	private final MeterRegistry meters;

	AcceptanceService(MessageRepository messages, TemplateResolver resolver, TemplateEngine engine,
			SuppressionHashes hashes, AcceptanceRateLimiter rateLimiter, AppProperties properties, JsonMapper jsonMapper,
			MeterRegistry meters) {
		this.messages = messages;
		this.resolver = resolver;
		this.engine = engine;
		this.hashes = hashes;
		this.rateLimiter = rateLimiter;
		this.properties = properties;
		this.jsonMapper = jsonMapper;
		this.canonicalMapper = jsonMapper.rebuild().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
		this.meters = meters;
	}

	/** The response body, and whether it was created now (202) or is an idempotent repeat (200). */
	record Outcome(EmailAccepted body, boolean created) {
	}

	@Transactional
	Outcome accept(UUID tenantId, SendEmailRequest request, String idempotencyKey) {
		MessageRepository.TenantSending tenant = messages.tenant(tenantId);
		if (tenant.paused()) {
			throw new ApiException(ErrorCode.TENANT_SENDING_PAUSED, "Sending is paused for this tenant.");
		}

		// Idempotency comes before anything that validates or counts (AC-08.7).
		if (idempotencyKey != null && !PRINTABLE_ASCII.matcher(idempotencyKey).matches()) {
			throw ApiException.validation(List.of(new FieldError("Idempotency-Key",
					"must be 1 to " + MAX_IDEMPOTENCY_KEY + " printable ASCII characters")));
		}
		String requestHash = requestHash(request);
		if (idempotencyKey == null) {
			meters.counter("email.requests.without.idempotency").increment();
		}
		else {
			Optional<MessageRepository.Stored> original = messages.findByIdempotencyKey(tenantId, idempotencyKey);
			if (original.isPresent()) {
				return repeat(original.get(), requestHash, request);
			}
		}

		validate(request, tenant);
		TemplateResolver.Resolved template = resolver.resolve(tenantId, request.templateKey(), request.templateVersion(),
				request.locale(), tenant.locale());
		if (template.fellBack()) {
			meters.counter("email.locale.fallback").increment();
		}
		List<FieldError> invalid = VariablesValidator.validate(template.schema(), request.variables());
		if (!invalid.isEmpty()) {
			throw new ApiException(ErrorCode.TEMPLATE_VARIABLES_INVALID, "The variables do not match the template.",
					invalid);
		}
		List<FieldError> unsafe = UrlPolicy.check(template.schema(), request.variables(), tenant.allowedLinkHosts());
		if (!unsafe.isEmpty()) {
			throw new ApiException(ErrorCode.UNSAFE_URL, "URL variables must be https on an allowed host.", unsafe);
		}
		try {
			// AC-07.5: render now so a broken template fails here; the worker renders again to send.
			engine.render(template.sources(), request.variables(), template.locale(), ZoneId.of(tenant.timezone()));
		}
		catch (TemplateEngine.RenderException ex) {
			throw new ApiException(ErrorCode.TEMPLATE_VARIABLES_INVALID, ex.getMessage());
		}

		Recipients recipients = applySuppressions(tenantId, request);
		rateLimiter.consume(tenantId);

		UUID id = Uuids.v7();
		String status = recipients.toSuppression() == null ? "QUEUED" : "FAILED";
		var message = new MessageRepository.NewMessage(id, tenantId, idempotencyKey, requestHash, template.templateId(),
				template.versionId(), template.category(), priority(template.category()), template.locale(),
				request.to().email().trim(), request.to().name(), recipients.cc(), recipients.bcc(),
				request.from() == null ? tenant.fromEmail() : request.from().trim(),
				request.fromName() == null ? tenant.fromName() : request.fromName(),
				request.replyTo() == null ? tenant.replyTo() : request.replyTo().trim(),
				// A message failed at acceptance is never rendered again, so its variables are not kept.
				"FAILED".equals(status) ? "{}" : jsonMapper.writeValueAsString(request.variables()), status,
				"FAILED".equals(status) ? "SUPPRESSED" : null, recipients.toSuppression(),
				request.tags() == null ? List.of() : request.tags(),
				request.metadata() == null || request.metadata().isNull() ? null
						: jsonMapper.writeValueAsString(request.metadata()),
				template.trackingEnabled());

		Optional<Instant> createdAt = messages.insert(message);
		if (createdAt.isEmpty()) {
			// A concurrent request with the same key committed first: answer as its repeat (AC-08.4).
			return repeat(messages.findByIdempotencyKey(tenantId, idempotencyKey).orElseThrow(), requestHash, request);
		}
		return new Outcome(new EmailAccepted(id, status, template.templateKey(), template.version(), template.locale(),
				message.toEmail(), recipients.dropped(), message.failureCode(), recipients.toSuppression(),
				createdAt.get()), true);
	}

	private Outcome repeat(MessageRepository.Stored original, String requestHash, SendEmailRequest request) {
		if (!original.requestHash().equals(requestHash)) {
			throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
					"This Idempotency-Key was already used with a different request.");
		}
		// Same body, so the dropped recipients are the requested ones that were not stored.
		List<String> dropped = new ArrayList<>();
		for (String address : concat(request.cc(), request.bcc())) {
			boolean kept = concat(original.cc(), original.bcc()).stream()
				.anyMatch(stored -> EmailAddresses.normalized(stored).equals(EmailAddresses.normalized(address)));
			if (!kept) {
				dropped.add(address.trim());
			}
		}
		String suppressionReason = "SUPPRESSED".equals(original.failureCode()) ? original.failureDetail() : null;
		return new Outcome(new EmailAccepted(original.id(), original.status(), original.templateKey(),
				original.templateVersion(), original.locale(), original.toEmail(), dropped, original.failureCode(),
				suppressionReason, original.createdAt()), false);
	}

	/** FR-07 and FR-09 field rules, grouped by the code each kind of failure maps to. */
	private void validate(SendEmailRequest request, MessageRepository.TenantSending tenant) {
		List<FieldError> injection = new ArrayList<>();
		lineBreak("to.email", request.to().email(), injection);
		lineBreak("to.name", request.to().name(), injection);
		lineBreak("from", request.from(), injection);
		lineBreak("fromName", request.fromName(), injection);
		lineBreak("replyTo", request.replyTo(), injection);
		each("cc", request.cc(), (field, value) -> lineBreak(field, value, injection));
		each("bcc", request.bcc(), (field, value) -> lineBreak(field, value, injection));
		each("tags", request.tags(), (field, value) -> lineBreak(field, value, injection));
		if (!injection.isEmpty()) {
			throw new ApiException(ErrorCode.HEADER_INJECTION_DETECTED, "Header fields may not contain line breaks.",
					injection);
		}

		List<FieldError> invalid = new ArrayList<>();
		if (request.sendAt() != null && !request.sendAt().isNull()) {
			invalid.add(new FieldError("sendAt", "is not supported yet: scheduled sending (FR-31) arrives in milestone H8"));
		}
		if (request.attachments() != null && !request.attachments().isNull()) {
			invalid.add(new FieldError("attachments", "are not supported (FR-30); send a signed link instead"));
		}
		if (request.fromName() != null && (request.fromName().isBlank() || request.fromName().length() > 80
				|| request.fromName().chars().anyMatch(c -> c == '<' || c == '>' || c == '@'))) {
			invalid.add(new FieldError("fromName", "must be 1 to 80 characters without <, > or @"));
		}
		each("tags", request.tags(), (field, value) -> {
			if (!TAG.matcher(value).matches()) {
				invalid.add(new FieldError(field, "must match [a-z0-9_-]{1,64}"));
			}
		});
		if (request.metadata() != null && !request.metadata().isNull()) {
			if (!request.metadata().isObject()) {
				invalid.add(new FieldError("metadata", "must be an object"));
			}
			else if (jsonMapper.writeValueAsString(request.metadata()).getBytes(StandardCharsets.UTF_8).length
					> MAX_METADATA_BYTES) {
				invalid.add(new FieldError("metadata", "must be at most 4 KB"));
			}
		}
		if (!request.variables().isObject()) {
			invalid.add(new FieldError("variables", "must be an object"));
		}
		if (request.locale() != null && !properties.tenancy().supportedLocales().contains(request.locale())) {
			invalid.add(new FieldError("locale", "must be one of " + properties.tenancy().supportedLocales()));
		}
		if (!invalid.isEmpty()) {
			throw ApiException.validation(invalid);
		}

		List<FieldError> addresses = new ArrayList<>();
		address("to.email", request.to().email(), addresses);
		address("from", request.from(), addresses);
		address("replyTo", request.replyTo(), addresses);
		each("cc", request.cc(), (field, value) -> address(field, value, addresses));
		each("bcc", request.bcc(), (field, value) -> address(field, value, addresses));
		if (!addresses.isEmpty()) {
			throw new ApiException(ErrorCode.INVALID_EMAIL_ADDRESS, "One or more addresses are invalid.", addresses);
		}

		if (request.from() != null && !EmailAddresses.domainIn(request.from(), tenant.allowedFromDomains())) {
			throw new ApiException(ErrorCode.FROM_DOMAIN_NOT_ALLOWED,
					"The sender domain is not in the tenant's allowedFromDomains.");
		}
		List<String> allowlist = properties.sending().allowedRecipientDomains();
		if (!allowlist.isEmpty()) {
			for (String recipient : concat(List.of(request.to().email()), concat(request.cc(), request.bcc()))) {
				if (!EmailAddresses.domainIn(recipient, allowlist)) {
					throw new ApiException(ErrorCode.RECIPIENT_NOT_ALLOWED_IN_ENV,
							"Recipients in this environment must belong to ALLOWED_RECIPIENT_DOMAINS.");
				}
			}
		}
	}

	private record Recipients(List<String> cc, List<String> bcc, List<String> dropped, String toSuppression) {
	}

	/**
	 * FR-10: a suppressed `to` fails the message (or is rejected in reject mode); suppressed cc/bcc
	 * addresses are dropped and reported.
	 */
	private Recipients applySuppressions(UUID tenantId, SendEmailRequest request) {
		List<String> all = concat(List.of(request.to().email()), concat(request.cc(), request.bcc()));
		Map<String, String> byHash = messages.suppressions(tenantId, all.stream().map(hashes::hash).toList());
		Map<String, String> reasonByAddress = new LinkedHashMap<>();
		for (String address : all) {
			String reason = byHash.get(HexFormat.of().formatHex(hashes.hash(address)));
			if (reason != null) {
				reasonByAddress.put(EmailAddresses.normalized(address), reason);
			}
		}
		String toReason = reasonByAddress.get(EmailAddresses.normalized(request.to().email()));
		if (toReason != null && properties.sending().rejectSuppressed()) {
			throw new ApiException(ErrorCode.RECIPIENT_SUPPRESSED, "The recipient is suppressed (" + toReason + ").");
		}
		List<String> dropped = new ArrayList<>();
		List<String> cc = keep(request.cc(), reasonByAddress, dropped);
		List<String> bcc = keep(request.bcc(), reasonByAddress, dropped);
		return new Recipients(cc, bcc, dropped, toReason);
	}

	private static List<String> keep(List<String> addresses, Map<String, String> suppressed, List<String> dropped) {
		List<String> kept = new ArrayList<>();
		for (String address : addresses == null ? List.<String>of() : addresses) {
			if (suppressed.containsKey(EmailAddresses.normalized(address))) {
				dropped.add(address.trim());
			}
			else {
				kept.add(address.trim());
			}
		}
		return kept;
	}

	/** SHA-256 of the canonical request: key order and whitespace do not matter (AC-08.2). */
	private String requestHash(SendEmailRequest request) {
		Map<String, Object> tree = canonicalMapper.convertValue(request, new TypeReference<Map<String, Object>>() {
		});
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(canonicalMapper.writeValueAsString(tree).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	static int priority(String category) {
		return switch (category) {
			case "SECURITY" -> 0;
			case "TRANSACTIONAL" -> 1;
			default -> 2;
		};
	}

	private static void lineBreak(String field, String value, List<FieldError> errors) {
		if (EmailAddresses.hasLineBreak(value)) {
			errors.add(new FieldError(field, "must not contain CR or LF"));
		}
	}

	private static void address(String field, String value, List<FieldError> errors) {
		if (value != null && !EmailAddresses.isValid(value.trim())) {
			errors.add(new FieldError(field, "is not a valid email address"));
		}
	}

	private static void each(String field, List<String> values, BiConsumer<String, String> check) {
		if (values == null) {
			return;
		}
		for (int i = 0; i < values.size(); i++) {
			check.accept(field + "[" + i + "]", values.get(i) == null ? "" : values.get(i));
		}
	}

	private static List<String> concat(List<String> a, List<String> b) {
		List<String> all = new ArrayList<>(a == null ? List.of() : a);
		all.addAll(b == null ? List.of() : b);
		return all;
	}

}
