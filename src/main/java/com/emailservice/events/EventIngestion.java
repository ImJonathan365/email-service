package com.emailservice.events;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

	private final MeterRegistry meters;

	EventIngestion(EventRepository events, MeterRegistry meters) {
		this.events = events;
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
		return outcome;
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
