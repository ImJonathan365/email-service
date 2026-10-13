package com.emailservice.maintenance;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.emailservice.common.persistence.SystemDataSource;

/** The eligible queue across tenants (docs/06 §7): what a worker could claim right now. */
@Component
class QueueState {

	record Snapshot(long pending, long oldestSeconds) {
	}

	private final JdbcClient jdbc;

	QueueState(@SystemDataSource JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Held tenants (suspended or paused) are not counted: their queue waits on purpose (AC-02.3). */
	Snapshot eligible() {
		return jdbc.sql("""
				SELECT count(*) AS pending,
				       coalesce(extract(epoch FROM now() - min(m.next_attempt_at)), 0)::bigint AS oldest_seconds
				FROM email_message m JOIN tenant t ON t.id = m.tenant_id
				WHERE m.status = 'QUEUED' AND m.next_attempt_at <= now()
				  AND t.status = 'ACTIVE' AND t.sending_paused_at IS NULL
				""")
			.query((rs, row) -> new Snapshot(rs.getLong("pending"), rs.getLong("oldest_seconds")))
			.single();
	}

}
