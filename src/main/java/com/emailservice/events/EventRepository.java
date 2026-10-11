package com.emailservice.events;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.emailservice.common.persistence.SystemDataSource;

/**
 * email_event and the email_message columns that events own, through email_system: a webhook
 * arrives without a tenant, which is only known after correlation (ADR-0008). Every write to a
 * correlated message still names its tenant_id.
 */
@Repository
class EventRepository {

	private final JdbcClient jdbc;

	EventRepository(@SystemDataSource JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	JdbcClient jdbc() {
		return jdbc;
	}

	/** The message an event belongs to, with what its effects need. */
	record MessageRef(UUID tenantId, UUID id, String tenantSlug, String status, String failureCode,
			String providerMessageId, String toEmail, boolean hasCopies) {
	}

	private static final String MESSAGE = """
			SELECT m.tenant_id, m.id, t.slug, m.status, m.failure_code, m.provider_message_id, m.to_email,
			       coalesce(cardinality(m.cc), 0) + coalesce(cardinality(m.bcc), 0) > 0 AS has_copies
			FROM email_message m JOIN tenant t ON t.id = m.tenant_id
			""";

	Optional<MessageRef> messageById(UUID id) {
		return jdbc.sql(MESSAGE + " WHERE m.id = :id").param("id", id).query(EventRepository::message).optional();
	}

	Optional<MessageRef> messageByProviderId(String provider, String providerMessageId) {
		return jdbc.sql(MESSAGE + " WHERE m.provider = :provider AND m.provider_message_id = :providerMessageId")
			.param("provider", provider)
			.param("providerMessageId", providerMessageId)
			.query(EventRepository::message)
			.optional();
	}

	/** Inserts the event once per provider event id; empty when it was already stored (AC-16.4). */
	Optional<UUID> insert(MessageRef message, String provider, String providerEventId, EventType type,
			String providerType, Instant occurredAt, String payload) {
		return jdbc.sql("""
				INSERT INTO email_event (id, tenant_id, message_id, provider, provider_event_id, type, provider_type,
				    occurred_at, payload)
				VALUES (:id, :tenantId, :messageId, :provider, :providerEventId, :type, :providerType, :occurredAt,
				    CAST(:payload AS jsonb))
				ON CONFLICT (provider, provider_event_id) DO NOTHING
				RETURNING id
				""")
			.param("id", UUID.randomUUID())
			.param("tenantId", message == null ? null : message.tenantId())
			.param("messageId", message == null ? null : message.id())
			.param("provider", provider)
			.param("providerEventId", providerEventId)
			.param("type", type.name())
			.param("providerType", providerType)
			.param("occurredAt", Timestamp.from(occurredAt))
			.param("payload", payload)
			.query(UUID.class)
			.optional();
	}

	private static MessageRef message(ResultSet rs, int row) throws SQLException {
		return new MessageRef(rs.getObject("tenant_id", UUID.class), rs.getObject("id", UUID.class),
				rs.getString("slug"), rs.getString("status"), rs.getString("failure_code"),
				rs.getString("provider_message_id"), rs.getString("to_email"), rs.getBoolean("has_copies"));
	}

}
