package com.emailservice.sending.worker;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.emailservice.audit.Actor;
import com.emailservice.audit.AuditAction;
import com.emailservice.audit.AuditLog;
import com.emailservice.common.alert.Alerts;
import com.emailservice.common.config.AppProperties;
import com.emailservice.common.config.RoleConditions.ConditionalOnWorkerRole;
import com.emailservice.events.MessageEvents;
import com.emailservice.provider.EmailSender;
import com.emailservice.provider.OutboundEmail;
import com.emailservice.provider.SendException;
import com.emailservice.provider.SendResult;
import com.emailservice.events.SuppressionHashes;
import com.emailservice.templates.engine.TemplateEngine;
import com.emailservice.templates.schema.SensitiveVariables;
import com.emailservice.templates.schema.VariablesSchema;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends one claimed message (FR-12, FR-13): re-checks tenant and suppressions, renders the pinned
 * version, waits for its rate-limit slot, calls the provider with Idempotency-Key = message id and
 * closes with the lock token. No row lock is held during the provider call (AC-11.5).
 */
@Component
@ConditionalOnWorkerRole
class MessageProcessor {

	private static final Logger log = LoggerFactory.getLogger(MessageProcessor.class);

	/** Resend keeps idempotency keys 24 h; no attempt may start later than this (AC-13.7). */
	static final Duration RETRY_WINDOW = Duration.ofHours(23);

	private static final Actor WORKER = new Actor(Actor.Type.SYSTEM, "worker", null, null);

	private final QueueRepository queue;

	private final EmailSender sender;

	private final TemplateEngine engine;

	private final SuppressionHashes hashes;

	private final JsonMapper jsonMapper;

	private final MeterRegistry meters;

	private final AuditLog auditLog;

	private final CircuitBreaker breaker;

	private final ProviderRateLimiter rateLimiter;

	private final RetryPolicy retryPolicy;

	private final MessageEvents messageEvents;

	private final Alerts alerts;

	private final ProviderOutcomes outcomes;

	private final DistributionSummary attempts;

	MessageProcessor(QueueRepository queue, EmailSender sender, TemplateEngine engine, SuppressionHashes hashes,
			JsonMapper jsonMapper, MeterRegistry meters, AuditLog auditLog, CircuitBreaker breaker,
			ProviderRateLimiter rateLimiter, MessageEvents messageEvents, Alerts alerts, ProviderOutcomes outcomes,
			AppProperties properties) {
		this.messageEvents = messageEvents;
		this.alerts = alerts;
		this.outcomes = outcomes;
		this.attempts = DistributionSummary.builder("email.send.attempts")
			.description("Attempts a message took until SENT or FAILED (AC-24.2)")
			.register(meters);
		this.queue = queue;
		this.sender = sender;
		this.engine = engine;
		this.hashes = hashes;
		this.jsonMapper = jsonMapper;
		this.meters = meters;
		this.auditLog = auditLog;
		this.breaker = breaker;
		this.rateLimiter = rateLimiter;
		SecureRandom random = new SecureRandom();
		this.retryPolicy = new RetryPolicy(properties.worker().retryBackoffSeconds(), properties.worker().maxAttempts(),
				random::nextDouble);
	}

	void process(QueueRepository.Claimed message) {
		MDC.put("messageId", message.id().toString());
		boolean reachedProvider = false;
		try {
			reachedProvider = doProcess(message);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			close(message, queue.release(message, "WORKER_STOPPED"));
		}
		catch (RuntimeException ex) {
			// Unexpected (database down, bug): the lock expires, the sweep requeues the message and the
			// provider deduplicates by message id (ADR-0010), so nothing is lost or sent twice.
			log.error("Unexpected error while processing a message; it will be reclaimed when its lock expires", ex);
		}
		finally {
			if (!reachedProvider) {
				breaker.onInconclusive();
			}
			MDC.remove("messageId");
			MDC.remove("tenantSlug");
		}
	}

	/** Returns whether the provider was called, which is what the circuit breaker learns from. */
	private boolean doProcess(QueueRepository.Claimed message) throws InterruptedException {
		QueueRepository.SendContext context = queue.context(message.tenantId(), message.templateVersionId());
		MDC.put("tenantSlug", context.tenantSlug());
		VariablesSchema schema = VariablesSchema.parse(jsonMapper.readTree(context.variablesSchema()), "variablesSchema");
		JsonNode variables = jsonMapper.readTree(message.variables());
		String withoutSensitive = jsonMapper.writeValueAsString(SensitiveVariables.strip(schema, variables));

		if (message.providerMessageId() != null) {
			// The provider already accepted it: never call again (AC-13.6).
			sent(message, queue.markSent(message, sender.name(), message.providerMessageId(), withoutSensitive, null));
			return false;
		}
		if (message.firstAttemptAt() != null && message.firstAttemptAt().plus(RETRY_WINDOW).isBefore(Instant.now())) {
			fail(message, "RETRY_WINDOW_EXCEEDED", "The first attempt is older than 23 hours.", withoutSensitive);
			return false;
		}
		if (context.tenantHeld()) {
			close(message, queue.release(message, "TENANT_HELD"));
			return false;
		}
		Optional<String> toSuppressed = queue.suppression(message.tenantId(), hashes.hash(message.toEmail()));
		if (toSuppressed.isPresent()) {
			fail(message, "SUPPRESSED", toSuppressed.get(), withoutSensitive);
			return false;
		}

		TemplateEngine.Rendered rendered;
		try {
			rendered = engine.render(
					new TemplateEngine.Sources(context.subjectTemplate(), context.htmlTemplate(), context.textTemplate()),
					variables, message.locale(), ZoneId.of(context.timezone()));
		}
		catch (TemplateEngine.RenderException ex) {
			// A render error never gets better by retrying (AC-12.5).
			fail(message, "RENDER_ERROR", ex.getMessage(), withoutSensitive);
			return false;
		}

		QueueRepository.StoredContent stored = context.storeRenderedContent()
				? storedCopy(context, schema, variables, message) : null;

		OutboundEmail email = new OutboundEmail(message.id(),
				new OutboundEmail.Address(message.fromEmail(), message.fromName()),
				message.replyTo() == null ? null : new OutboundEmail.Address(message.replyTo(), null),
				new OutboundEmail.Address(message.toEmail(), message.toName()), notSuppressed(message, message.cc()),
				notSuppressed(message, message.bcc()), rendered.subject(), rendered.html(), rendered.text(),
				message.tags());
		if (breaker.blocksSending()) {
			// Claimed in the same batch as the failure that opened the breaker.
			close(message, queue.release(message, "CIRCUIT_OPEN"));
			return false;
		}
		rateLimiter.acquire();
		Timer.Sample call = Timer.start(meters);
		try {
			SendResult result = sender.send(email);
			providerCall(call, ProviderOutcomes.Outcome.SUCCESS);
			breaker.onSuccess();
			boolean closed = queue.markSent(message, result.provider(), result.providerMessageId(), withoutSensitive,
					stored);
			if (closed) {
				Timer.builder("email.time.to.sent")
					.description("Acceptance to SENT (NFR-19)")
					.tag("category", message.category())
					.register(meters)
					.record(Duration.between(message.createdAt(), Instant.now()));
			}
			sent(message, closed);
		}
		catch (SendException ex) {
			providerCall(call, switch (ex.kind()) {
				case TRANSIENT -> ProviderOutcomes.Outcome.TRANSIENT;
				case CONFIGURATION -> ProviderOutcomes.Outcome.CONFIGURATION;
				case PERMANENT, TENANT_CONFIGURATION -> ProviderOutcomes.Outcome.PERMANENT;
			});
			onSendFailure(message, ex, withoutSensitive);
		}
		return true;
	}

	/** Provider latency by outcome (AC-24.2) and the last five minutes for its health indicator. */
	private void providerCall(Timer.Sample call, ProviderOutcomes.Outcome outcome) {
		call.stop(Timer.builder("email.provider.latency")
			.description("Duration of a provider call")
			.tag("provider", sender.name())
			.tag("outcome", outcome.name())
			.register(meters));
		outcomes.record(outcome);
	}

	private void onSendFailure(QueueRepository.Claimed message, SendException ex, String withoutSensitive) {
		switch (ex.kind()) {
			case CONFIGURATION -> {
				// Every message would fail the same way: stop sending at once, and do not let the
				// broken setup burn attempts (docs/05 §4, "Configuración").
				breaker.onConfigurationError();
				configurationError(ex.providerError());
				alerts.raise("PROVIDER_CONFIGURATION", String.valueOf(ex.providerError()), "The provider rejected our own "
						+ "configuration (" + ex.providerError() + "); check RESEND_API_KEY and RESEND_API_URL. "
						+ "Sending is paused by the circuit breaker");
				close(message, queue.release(message, ex.code()));
			}
			case TENANT_CONFIGURATION -> {
				// The provider works; only this tenant's sender domain is unverified.
				breaker.onSuccess();
				pauseTenant(message);
				close(message, queue.release(message, ex.code()));
			}
			case PERMANENT -> {
				breaker.onSuccess();
				if ("PROVIDER_IDEMPOTENCY_CONFLICT".equals(ex.code())) {
					// Same key, different body: the render was not deterministic (AC-13.8).
					meters.counter("email.provider.idempotency.conflict").increment();
					alerts.raise("IDEMPOTENCY_CONFLICT", message.id().toString(),
							"Provider idempotency conflict: the same message produced a different body (AC-12.7)");
				}
				fail(message, ex.code(), ex.getMessage(), withoutSensitive);
			}
			case TRANSIENT -> {
				breaker.onTransientFailure();
				retryOrFail(message, ex, withoutSensitive);
			}
		}
	}

	/**
	 * The copy a tenant with storeRenderedContent keeps (AC-19.3): rendered a second time with the
	 * sensitive values as [REDACTED], so the stored content never holds them. If that render fails
	 * (e.g. a helper cannot format the marker), nothing is stored; the send itself is unaffected.
	 */
	private QueueRepository.StoredContent storedCopy(QueueRepository.SendContext context, VariablesSchema schema,
			JsonNode variables, QueueRepository.Claimed message) {
		try {
			TemplateEngine.Rendered redacted = engine.render(
					new TemplateEngine.Sources(context.subjectTemplate(), context.htmlTemplate(), context.textTemplate()),
					SensitiveVariables.redact(schema, variables), message.locale(), ZoneId.of(context.timezone()));
			return new QueueRepository.StoredContent(redacted.subject(), redacted.html(), redacted.text());
		}
		catch (TemplateEngine.RenderException ex) {
			log.warn("Rendered content not stored: the redacted copy does not render");
			return null;
		}
	}

	private void retryOrFail(QueueRepository.Claimed message, SendException ex, String withoutSensitive) {
		if (retryPolicy.exhausted(message.attempts())) {
			fail(message, "MAX_ATTEMPTS_EXCEEDED", "Last error: " + ex.code(), withoutSensitive);
			return;
		}
		Instant next = Instant.now().plus(retryPolicy.delayAfter(message.attempts(), ex.retryAfter()));
		if (message.firstAttemptAt() != null && next.isAfter(message.firstAttemptAt().plus(RETRY_WINDOW))) {
			fail(message, "RETRY_WINDOW_EXCEEDED", "Last error: " + ex.code(), withoutSensitive);
			return;
		}
		close(message, queue.markRetry(message, ex.code(), next));
	}

	private void pauseTenant(QueueRepository.Claimed message) {
		if (queue.pauseForUnverifiedDomain(message.tenantId())) {
			auditLog.record(queue.jdbc(), WORKER, AuditAction.TENANT_PAUSED, message.tenantId(), "tenant",
					message.tenantId().toString(), Map.of("reason", "PROVIDER_DOMAIN"));
			configurationError("domain_not_verified");
			alerts.raise("TENANT_PAUSED", message.tenantId().toString(), "The provider reports the tenant's sender "
					+ "domain as unverified; the tenant is paused (PROVIDER_DOMAIN) until it is resumed through the admin API");
		}
	}

	private void configurationError(String error) {
		meters.counter("email.provider.config.error", "error", error == null ? "unknown" : error).increment();
	}

	private void fail(QueueRepository.Claimed message, String code, String detail, String withoutSensitive) {
		boolean closed = queue.markFailed(message, code, detail, withoutSensitive);
		close(message, closed);
		if (closed) {
			attempts.record(message.attempts());
			messageEvents.checkAfterFailed(message.tenantId(), message.id(), code);
		}
	}

	/** Events that arrived while this worker owned the message apply now, after its own close. */
	private void sent(QueueRepository.Claimed message, boolean closed) {
		close(message, closed);
		if (closed) {
			attempts.record(message.attempts());
			messageEvents.applyStoredAfterSent(message.tenantId(), message.id());
		}
	}

	/** A close that changed no row means another worker owns the message now: only record it. */
	private void close(QueueRepository.Claimed message, boolean closed) {
		if (!closed) {
			queue.logLostLock(message);
			meters.counter("email.lost.lock").increment();
			alerts.raise("LOST_LOCK", "worker",
					"Lost the lock of a message before closing it; leaving it to its new owner");
		}
	}

	/** Re-checked right before sending, so a suppression created after acceptance holds (AC-10.6). */
	private List<String> notSuppressed(QueueRepository.Claimed message, List<String> addresses) {
		return addresses.stream()
			.filter(address -> queue.suppression(message.tenantId(), hashes.hash(address)).isEmpty())
			.toList();
	}

}
