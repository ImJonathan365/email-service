package com.emailservice.events;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.emailservice.provider.WebhookVerifier;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Stores one verified provider event and applies its effects in the same transaction (FR-17): the
 * event row is the dedupe point, so a replay finds it and does nothing (AC-16.4).
 */
@Service
class EventIngestion {

	private static final Logger log = LoggerFactory.getLogger(EventIngestion.class);

	enum Outcome {

		PROCESSED, DUPLICATE, ORPHAN

	}

	private final EventRepository events;

	private final MessageTransitions transitions;

	private final GlobalSuppressions suppressions;

	private final MeterRegistry meters;

	EventIngestion(EventRepository events, MessageTransitions transitions, GlobalSuppressions suppressions,
			MeterRegistry meters) {
		this.events = events;
		this.transitions = transitions;
		this.suppressions = suppressions;
		this.meters = meters;
	}

	@Transactional("systemTransactionManager")
	Outcome ingest(String provider, WebhookVerifier.Verified verified, ResendEvent event) {
		EventRepository.MessageRef message = correlate(provider, verified, event).orElse(null);
		Optional<UUID> stored = events.insert(message, provider, verified.eventId(), event.type(),
				event.providerType(), event.occurredAt(), event.minimized().toString());
		Outcome outcome = stored.isEmpty() ? Outcome.DUPLICATE : message == null ? Outcome.ORPHAN : Outcome.PROCESSED;
		meters.counter("email.webhook.events", "type", event.type().name(), "outcome", outcome.name().toLowerCase())
			.increment();
		if (outcome != Outcome.DUPLICATE) {
			// Suppressions do not depend on the state change, nor on finding the message (AC-17.4).
			applySuppressions(message, event);
		}
		if (outcome == Outcome.PROCESSED) {
			MDC.put("tenantSlug", message.tenantSlug());
			MDC.put("messageId", message.id().toString());
			try {
				applyToMessage(message, event);
			}
			finally {
				MDC.remove("tenantSlug");
				MDC.remove("messageId");
			}
		}
		return outcome;
	}

	private void applySuppressions(EventRepository.MessageRef message, ResendEvent event) {
		UUID sourceTenant = message == null ? null : message.tenantId();
		UUID sourceMessage = message == null ? null : message.id();
		switch (event.providerType()) {
			case "suppression.added" -> event.suppressionEmail()
				.ifPresent(email -> suppressions.add(email, "PROVIDER", sourceTenant, sourceMessage));
			case "suppression.removed" -> event.suppressionEmail().ifPresent(suppressions::removeProviderMirror);
			default -> {
				String reason = switch (event.type()) {
					case BOUNCED -> "HARD_BOUNCE";
					case COMPLAINED -> "COMPLAINT";
					case SUPPRESSED -> "PROVIDER";
					default -> null;
				};
				if (reason != null) {
					for (String address : addressesToSuppress(message, event)) {
						suppressions.add(address, reason, sourceTenant, sourceMessage);
					}
				}
			}
		}
	}

	/**
	 * The event does not say which recipient bounced or complained. With cc/bcc it could be any of
	 * them, so nothing is guessed: the provider's suppression.added names the exact address (owner,
	 * H6). Without copies it can only be the to address. An orphan has only the provider's list.
	 */
	private static List<String> addressesToSuppress(EventRepository.MessageRef message, ResendEvent event) {
		if (message == null) {
			return event.recipients();
		}
		return message.hasCopies() ? List.of() : List.of(message.toEmail());
	}

	/** The state change and the side effects are independent: a late event still has its effects (§2). */
	private void applyToMessage(EventRepository.MessageRef message, ResendEvent event) {
		transitions.apply(message.tenantId(), message.id(), event.type(), event.occurredAt());
		transitions.flagIfFailedButHandled(message.id(), message.status(), message.failureCode(), event.type());
		switch (event.type()) {
			case DELIVERY_DELAYED -> meters.counter("email.delivery.delayed", "tenant", message.tenantSlug()).increment();
			case COMPLAINED -> {
				meters.counter("email.complaints", "tenant", message.tenantSlug()).increment();
				log.warn("Spam complaint for message {} of tenant {}", message.id(), message.tenantSlug());
			}
			default -> {
			}
		}
	}

	/**
	 * Our message id travels back as a tag even before the worker stored the provider's id, so it
	 * is tried first; the provider's email id confirms it or stands in for it.
	 */
	private Optional<EventRepository.MessageRef> correlate(String provider, WebhookVerifier.Verified verified,
			ResendEvent event) {
		Optional<EventRepository.MessageRef> byTag = event.messageIdTag().flatMap(events::messageById);
		Optional<EventRepository.MessageRef> byEmailId = event.emailId()
			.flatMap(id -> events.messageByProviderId(provider, id));
		if (byTag.isEmpty()) {
			return byEmailId;
		}
		EventRepository.MessageRef tagged = byTag.get();
		boolean otherMessage = byEmailId.isPresent() && !byEmailId.get().id().equals(tagged.id());
		boolean otherEmail = event.emailId().isPresent() && tagged.providerMessageId() != null
				&& !Objects.equals(tagged.providerMessageId(), event.emailId().get());
		if (otherMessage || otherEmail) {
			meters.counter("email.webhook.correlation.conflict").increment();
			log.warn("Webhook event {} names one message by tag and another by provider id; stored as orphan",
					verified.eventId());
			return Optional.empty();
		}
		return byTag;
	}

}
