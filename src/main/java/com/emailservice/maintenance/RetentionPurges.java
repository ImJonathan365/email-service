package com.emailservice.maintenance;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.emailservice.common.config.AppProperties;
import com.emailservice.common.config.RoleConditions.ConditionalOnWorkerRole;
import com.emailservice.common.persistence.SystemDataSource;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Retention of FR-23 (docs/06 §8). Every purge runs in batches, each in its own short transaction
 * under pg_try_advisory_xact_lock, so a second worker instance skips instead of competing; every
 * statement is idempotent. Messages still QUEUED or SENDING are never deleted (owner, H7), and
 * suppressions are never purged by age (AC-23.3). Tag rows go with their messages (ON DELETE
 * CASCADE); events are deleted with them in the same statement.
 */
@Component
@ConditionalOnWorkerRole
class RetentionPurges {

	private static final Logger log = LoggerFactory.getLogger(RetentionPurges.class);

	static final int BATCH = 5_000;

	private final JdbcClient jdbc;

	private final TransactionTemplate transactions;

	private final MeterRegistry meters;

	private final AppProperties.Worker settings;

	RetentionPurges(@SystemDataSource JdbcClient jdbc, @SystemDataSource PlatformTransactionManager transactionManager,
			MeterRegistry meters, AppProperties properties) {
		this.jdbc = jdbc;
		this.transactions = new TransactionTemplate(transactionManager);
		this.meters = meters;
		this.settings = properties.worker();
	}

	/** AC-23.5: variables become {} some days after a terminal state or after being final by time. */
	long purgeVariables() {
		long terminal = inBatches("variables", "purgeVariables", () -> jdbc.sql("""
				WITH due AS (
				    SELECT id FROM email_message
				    WHERE variables_purged_at IS NULL AND finalized_at < now() - :days * interval '1 day'
				    LIMIT :batch)
				UPDATE email_message m SET variables = '{}'::jsonb, variables_purged_at = now()
				FROM due WHERE m.id = due.id
				""").param("days", settings.variablesRetentionDays()).param("batch", BATCH).update());
		long finalByTime = inBatches("variables", "purgeVariables", () -> jdbc.sql("""
				WITH due AS (
				    SELECT id FROM email_message
				    WHERE variables_purged_at IS NULL AND status IN ('SENT', 'DELIVERED')
				      AND sent_at < now() - :finalHours * interval '1 hour' - :days * interval '1 day'
				    LIMIT :batch)
				UPDATE email_message m SET variables = '{}'::jsonb, variables_purged_at = now()
				FROM due WHERE m.id = due.id
				""")
			.param("finalHours", settings.sentFinalAfterHours())
			.param("days", settings.variablesRetentionDays())
			.param("batch", BATCH)
			.update());
		return terminal + finalByTime;
	}

	/** AC-08.5: an idempotency key protects 24 h; after that the key and request hash are dropped. */
	long purgeIdempotencyKeys() {
		return inBatches("idempotency_key", "purgeIdempotencyKeys", () -> jdbc.sql("""
				WITH due AS (
				    SELECT id FROM email_message
				    WHERE idempotency_key IS NOT NULL AND created_at < now() - :hours * interval '1 hour'
				    LIMIT :batch)
				UPDATE email_message m SET idempotency_key = NULL, request_hash = NULL
				FROM due WHERE m.id = due.id
				""").param("hours", settings.idempotencyRetentionHours()).param("batch", BATCH).update());
	}

	/** The daily purge: tenant retention, rendered content, orphans, counters and audit. */
	long purgeExpiredData() {
		long messages = inBatches("email_message", "purgeExpiredData", () -> jdbc.sql("""
				WITH due AS (
				    SELECT m.tenant_id, m.id FROM email_message m JOIN tenant t ON t.id = m.tenant_id
				    WHERE m.created_at < now() - interval '7 days'
				      AND m.created_at < now() - t.retention_days * interval '1 day'
				      AND m.status NOT IN ('QUEUED', 'SENDING')
				    LIMIT :batch),
				events AS (
				    DELETE FROM email_event e USING due WHERE e.tenant_id = due.tenant_id AND e.message_id = due.id)
				DELETE FROM email_message m USING due WHERE m.id = due.id
				""").param("batch", BATCH).update());
		long rendered = inBatches("rendered_content", "purgeExpiredData", () -> jdbc.sql("""
				WITH due AS (
				    SELECT id FROM email_message
				    WHERE (rendered_subject IS NOT NULL OR rendered_html IS NOT NULL OR rendered_text IS NOT NULL)
				      AND created_at < now() - interval '30 days'
				    LIMIT :batch)
				UPDATE email_message m SET rendered_subject = NULL, rendered_html = NULL, rendered_text = NULL
				FROM due WHERE m.id = due.id
				""").param("batch", BATCH).update());
		long orphans = inBatches("orphan_events", "purgeExpiredData", () -> jdbc.sql("""
				DELETE FROM email_event WHERE id IN (
				    SELECT id FROM email_event
				    WHERE message_id IS NULL AND received_at < now() - interval '30 days'
				    LIMIT :batch)
				""").param("batch", BATCH).update());
		long counters = inBatches("rate_limit_counter", "purgeExpiredData", () -> jdbc.sql("""
				DELETE FROM rate_limit_counter WHERE ctid IN (
				    SELECT ctid FROM rate_limit_counter WHERE window_start < now() - interval '2 days' LIMIT :batch)
				""").param("batch", BATCH).update());
		long audit = inBatches("audit_log", "purgeExpiredData",
				() -> jdbc.sql("SELECT purge_expired_audit_log(:batch)").param("batch", BATCH).query(Integer.class).single());
		return messages + rendered + orphans + counters + audit;
	}

	/**
	 * Repeats one batch until it touches fewer than BATCH rows. Each batch is its own transaction
	 * holding the task's advisory lock; if another instance holds it, this round stops.
	 */
	private long inBatches(String data, String task, Supplier<Integer> batch) {
		long total = 0;
		while (true) {
			Integer rows = transactions.execute(status -> {
				Boolean acquired = jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtext(:task))")
					.param("task", task)
					.query(Boolean.class)
					.single();
				return Boolean.TRUE.equals(acquired) ? batch.get() : null;
			});
			if (rows == null) {
				break;
			}
			total += rows;
			if (rows < BATCH) {
				break;
			}
		}
		if (total > 0) {
			meters.counter("email.purged.rows", "data", data).increment(total);
			log.info("Purged {} rows ({}, {})", total, data, task);
		}
		return total;
	}

}
