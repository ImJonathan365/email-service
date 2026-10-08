package com.emailservice.sending.worker;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.emailservice.common.config.AppProperties;
import com.emailservice.common.config.RoleConditions.ConditionalOnWorkerRole;
import com.emailservice.provider.EmailSender;
import com.emailservice.provider.OutboundEmail;
import com.emailservice.provider.SendException;
import com.emailservice.provider.SendResult;
import com.emailservice.sending.SuppressionHashes;
import com.emailservice.templates.engine.TemplateEngine;
import com.emailservice.templates.schema.SensitiveVariables;
import com.emailservice.templates.schema.VariablesSchema;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends one claimed message (FR-12, FR-13): re-checks tenant and suppressions, renders the pinned
 * version, calls the provider with Idempotency-Key = message id and closes with the lock token.
 * No row lock is held during the provider call: the claim already committed (AC-11.5).
 */
@Component
@ConditionalOnWorkerRole
class MessageProcessor {

	private static final Logger log = LoggerFactory.getLogger(MessageProcessor.class);

	/** Resend keeps idempotency keys 24 h; no attempt may start later than this (AC-13.7). */
	static final Duration RETRY_WINDOW = Duration.ofHours(23);

	private final QueueRepository queue;

	private final EmailSender sender;

	private final TemplateEngine engine;

	private final SuppressionHashes hashes;

	private final JsonMapper jsonMapper;

	private final MeterRegistry meters;

	private final RetryPolicy retryPolicy;

	MessageProcessor(QueueRepository queue, EmailSender sender, TemplateEngine engine, SuppressionHashes hashes,
			JsonMapper jsonMapper, MeterRegistry meters, AppProperties properties) {
		this.queue = queue;
		this.sender = sender;
		this.engine = engine;
		this.hashes = hashes;
		this.jsonMapper = jsonMapper;
		this.meters = meters;
		SecureRandom random = new SecureRandom();
		this.retryPolicy = new RetryPolicy(properties.worker().retryBackoffSeconds(), properties.worker().maxAttempts(),
				random::nextDouble);
	}

	void process(QueueRepository.Claimed message) {
		MDC.put("messageId", message.id().toString());
		try {
			doProcess(message);
		}
		catch (RuntimeException ex) {
			// Unexpected (database down, bug): the lock expires, the sweep requeues the message and the
			// provider deduplicates by message id (ADR-0010), so nothing is lost or sent twice.
			log.error("Unexpected error while processing a message; it will be reclaimed when its lock expires", ex);
		}
		finally {
			MDC.remove("messageId");
		}
	}

	private void doProcess(QueueRepository.Claimed message) {
		QueueRepository.SendContext context = queue.context(message.tenantId(), message.templateVersionId());
		VariablesSchema schema = VariablesSchema.parse(jsonMapper.readTree(context.variablesSchema()), "variablesSchema");
		JsonNode variables = jsonMapper.readTree(message.variables());
		String withoutSensitive = jsonMapper.writeValueAsString(SensitiveVariables.strip(schema, variables));

		if (message.providerMessageId() != null) {
			// The provider already accepted it: never call again (AC-13.6).
			close(message, queue.markSent(message, sender.name(), message.providerMessageId(), withoutSensitive));
			return;
		}
		if (message.firstAttemptAt() != null && message.firstAttemptAt().plus(RETRY_WINDOW).isBefore(Instant.now())) {
			fail(message, "RETRY_WINDOW_EXCEEDED", "The first attempt is older than 23 hours.", withoutSensitive);
			return;
		}
		if (context.tenantHeld()) {
			close(message, queue.release(message));
			return;
		}
		Optional<String> toSuppressed = queue.suppression(message.tenantId(), hashes.hash(message.toEmail()));
		if (toSuppressed.isPresent()) {
			fail(message, "SUPPRESSED", toSuppressed.get(), withoutSensitive);
			return;
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
			return;
		}

		OutboundEmail email = new OutboundEmail(message.id(),
				new OutboundEmail.Address(message.fromEmail(), message.fromName()),
				message.replyTo() == null ? null : new OutboundEmail.Address(message.replyTo(), null),
				new OutboundEmail.Address(message.toEmail(), message.toName()), notSuppressed(message, message.cc()),
				notSuppressed(message, message.bcc()), rendered.subject(), rendered.html(), rendered.text(),
				message.tags());
		try {
			SendResult result = sender.send(email);
			boolean closed = queue.markSent(message, result.provider(), result.providerMessageId(), withoutSensitive);
			if (closed) {
				Timer.builder("email.time.to.sent")
					.description("Acceptance to SENT (NFR-19)")
					.tag("category", message.category())
					.register(meters)
					.record(Duration.between(message.createdAt(), Instant.now()));
			}
			close(message, closed);
		}
		catch (SendException ex) {
			onSendFailure(message, ex, withoutSensitive);
		}
	}

	private void onSendFailure(QueueRepository.Claimed message, SendException ex, String withoutSensitive) {
		if (ex.kind() == SendException.Kind.PERMANENT) {
			fail(message, ex.code(), ex.getMessage(), withoutSensitive);
			return;
		}
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

	private void fail(QueueRepository.Claimed message, String code, String detail, String withoutSensitive) {
		close(message, queue.markFailed(message, code, detail, withoutSensitive));
	}

	/** A close that changed no row means another worker owns the message now: only record it. */
	private void close(QueueRepository.Claimed message, boolean closed) {
		if (!closed) {
			queue.logLostLock(message);
			meters.counter("email.lost.lock").increment();
			log.warn("Lost the lock of a message before closing it; leaving it to its new owner");
		}
	}

	/** Re-checked right before sending, so a suppression created after acceptance holds (AC-10.6). */
	private List<String> notSuppressed(QueueRepository.Claimed message, List<String> addresses) {
		return addresses.stream()
			.filter(address -> queue.suppression(message.tenantId(), hashes.hash(address)).isEmpty())
			.toList();
	}

}
