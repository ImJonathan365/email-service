package com.emailservice.events;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.emailservice.common.persistence.SystemDataSource;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * What a provider event does to its message (docs/02 §2 and §2.1). Every UPDATE names the states
 * it may leave, so the precedence QUEUED < SENDING < SENT < DELIVERED < BOUNCED = COMPLAINED is
 * enforced by the database: a late or repeated event changes nothing. QUEUED and SENDING belong
 * to the worker and are never touched here.
 */
@Component
class MessageTransitions {

	private static final Logger log = LoggerFactory.getLogger(MessageTransitions.class);

	/** Events that prove the provider accepted and handled the message. */
	static final Set<EventType> PROVIDER_HANDLED = EnumSet.of(EventType.SENT, EventType.DELIVERED,
			EventType.DELIVERY_DELAYED, EventType.BOUNCED, EventType.COMPLAINED, EventType.OPENED, EventType.CLICKED);

	private final JdbcClient jdbc;

	private final MeterRegistry meters;

	MessageTransitions(@SystemDataSource JdbcClient jdbc, MeterRegistry meters) {
		this.jdbc = jdbc;
		this.meters = meters;
	}

	/** Applies the state change of one event; returns whether the message changed. */
	boolean apply(UUID tenantId, UUID messageId, EventType type, Instant occurredAt) {
		return switch (type) {
			case DELIVERED -> update("""
					UPDATE email_message SET status = 'DELIVERED', delivered_at = :at, updated_at = now()
					WHERE tenant_id = :tenantId AND id = :id AND status = 'SENT'
					""", tenantId, messageId, occurredAt);
			case BOUNCED, COMPLAINED -> update("""
					UPDATE email_message SET status = '%s', finalized_at = now(), updated_at = now()
					WHERE tenant_id = :tenantId AND id = :id AND status IN ('SENT', 'DELIVERED')
					""".formatted(type.name()), tenantId, messageId, null);
			case FAILED -> failFromSent(tenantId, messageId, "PROVIDER_FAILED", "The provider reported email.failed.");
			case SUPPRESSED -> failFromSent(tenantId, messageId, "PROVIDER_SUPPRESSED",
					"The provider skipped the address: it is on its suppression list.");
			// Counters only for messages that asked for tracking (AC-17.3); never a state.
			case OPENED -> update("""
					UPDATE email_message SET first_opened_at = coalesce(first_opened_at, :at),
					    open_count = open_count + 1, updated_at = now()
					WHERE tenant_id = :tenantId AND id = :id AND tracking_enabled
					""", tenantId, messageId, occurredAt);
			case CLICKED -> update("""
					UPDATE email_message SET first_clicked_at = coalesce(first_clicked_at, :at),
					    click_count = click_count + 1, updated_at = now()
					WHERE tenant_id = :tenantId AND id = :id AND tracking_enabled
					""", tenantId, messageId, occurredAt);
			case SENT, DELIVERY_DELAYED, OTHER -> false;
		};
	}

	/**
	 * The rare case of a message we gave up on (e.g. RETRY_WINDOW_EXCEEDED) that the provider did
	 * send after all. Its state stays FAILED; it is surfaced so an operator can look (owner, H6).
	 */
	void flagIfFailedButHandled(UUID messageId, String status, String failureCode, EventType type) {
		boolean failedByUs = "FAILED".equals(status) && failureCode != null && !failureCode.startsWith("PROVIDER_");
		if (failedByUs && PROVIDER_HANDLED.contains(type)) {
			meters.counter("email.event.after.failure").increment();
			log.warn("Message {} is FAILED ({}) but the provider reports it as handled ({})", messageId, failureCode, type);
		}
	}

	private boolean failFromSent(UUID tenantId, UUID messageId, String code, String detail) {
		return jdbc.sql("""
				UPDATE email_message SET status = 'FAILED', failure_code = :code, failure_detail = :detail,
				    finalized_at = now(), updated_at = now()
				WHERE tenant_id = :tenantId AND id = :id AND status = 'SENT'
				""")
			.param("code", code)
			.param("detail", detail)
			.param("tenantId", tenantId)
			.param("id", messageId)
			.update() == 1;
	}

	/** {@code at} binds :at for the statements that record when it happened; null otherwise. */
	private boolean update(String sql, UUID tenantId, UUID messageId, Instant at) {
		var statement = jdbc.sql(sql).param("tenantId", tenantId).param("id", messageId);
		if (at != null) {
			statement = statement.param("at", Timestamp.from(at));
		}
		return statement.update() == 1;
	}

}
