package com.emailservice.events;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.emailservice.common.persistence.SystemDataSource;

/**
 * What the worker asks of events when it closes a message. An event can arrive while the worker
 * still owns the message (for instance after a lost response): it is stored without touching the
 * state, and applied here once the worker has closed the message itself, so fencing is unchanged.
 */
@Component
public class MessageEvents {

	private final JdbcClient jdbc;

	private final MessageTransitions transitions;

	MessageEvents(@SystemDataSource JdbcClient jdbc, MessageTransitions transitions) {
		this.jdbc = jdbc;
		this.transitions = transitions;
	}

	private record StoredEvent(EventType type, Timestamp occurredAt) {
	}

	/** After SENT: applies the state events already stored for the message, oldest first. */
	@Transactional("systemTransactionManager")
	public void applyStoredAfterSent(UUID tenantId, UUID messageId) {
		for (StoredEvent event : stored(tenantId, messageId)) {
			// Opened/clicked counters were applied on arrival; only state changes are pending.
			if (event.type() != EventType.OPENED && event.type() != EventType.CLICKED) {
				transitions.apply(tenantId, messageId, event.type(), event.occurredAt().toInstant());
			}
		}
	}

	/** After FAILED: warns if the provider had already reported the message as handled. */
	@Transactional(transactionManager = "systemTransactionManager", readOnly = true)
	public void checkAfterFailed(UUID tenantId, UUID messageId, String failureCode) {
		stored(tenantId, messageId).stream()
			.map(StoredEvent::type)
			.filter(MessageTransitions.PROVIDER_HANDLED::contains)
			.findFirst()
			.ifPresent(type -> transitions.flagIfFailedButHandled(messageId, "FAILED", failureCode, type));
	}

	private List<StoredEvent> stored(UUID tenantId, UUID messageId) {
		return jdbc.sql("""
				SELECT type, occurred_at FROM email_event
				WHERE tenant_id = :tenantId AND message_id = :messageId
				ORDER BY occurred_at, received_at
				""")
			.param("tenantId", tenantId)
			.param("messageId", messageId)
			.query((rs, row) -> new StoredEvent(EventType.valueOf(rs.getString("type")), rs.getTimestamp("occurred_at")))
			.list();
	}

}
