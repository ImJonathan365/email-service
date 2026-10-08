package com.emailservice.sending.worker;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.emailservice.common.persistence.SystemDataSource;

/**
 * The email_message queue through email_system, which must see every tenant's messages
 * (ADR-0008, ADR-0010). Every close is fenced by lock_token: a worker that lost its lock changes
 * nothing (AC-12.3).
 */
@Repository
class QueueRepository {

	private static final String ENTRY = "jsonb_build_object('at', now(), 'attempt', attempts, 'code', CAST(:code AS text))";

	/** Appends to attempt_log and keeps the last 10 entries (AC-13.4). */
	private static final String APPEND_LOG = "jsonb_path_query_array(coalesce(attempt_log, '[]'::jsonb) || " + ENTRY
			+ ", '$[last - 9 to last]')";

	private final JdbcClient jdbc;

	QueueRepository(@SystemDataSource JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	record Claimed(UUID id, UUID tenantId, UUID templateVersionId, String category, int priority, String locale,
			String toEmail,
			String toName, List<String> cc, List<String> bcc, String fromEmail, String fromName, String replyTo,
			String variables, List<String> tags, int attempts, Instant firstAttemptAt, UUID lockToken,
			String providerMessageId, Instant createdAt) {

		@Override
		public String toString() {
			return "Claimed[id=" + id + ", attempts=" + attempts + "]";
		}

	}

	/**
	 * ADR-0010 claim: one short autocommit statement, never with a network call inside. Takes at
	 * most {@code freeSlots} eligible messages of active, unpaused tenants by priority, counting
	 * the attempt now so a message that kills the worker cannot loop forever.
	 */
	List<Claimed> claim(int freeSlots, int lockTimeoutSeconds, String instanceId) {
		return jdbc.sql("""
				WITH picked AS (
				    SELECT m.id
				    FROM email_message m
				    JOIN tenant t ON t.id = m.tenant_id
				    WHERE m.status = 'QUEUED'
				      AND m.next_attempt_at <= now()
				      AND t.status = 'ACTIVE' AND t.sending_paused_at IS NULL
				    ORDER BY m.priority, m.next_attempt_at
				    FOR UPDATE OF m SKIP LOCKED
				    LIMIT :freeSlots
				)
				UPDATE email_message m
				SET status           = 'SENDING',
				    attempts         = m.attempts + 1,
				    first_attempt_at = coalesce(m.first_attempt_at, now()),
				    lock_token       = gen_random_uuid(),
				    lock_expires_at  = now() + make_interval(secs => :lockTimeout),
				    locked_by        = :instanceId,
				    updated_at       = now()
				FROM picked
				WHERE m.id = picked.id
				RETURNING m.id, m.tenant_id, m.template_version_id, m.category, m.locale, m.to_email, m.to_name, m.cc,
				    m.bcc, m.from_email, m.from_name, m.reply_to, m.variables::text AS variables, m.tags, m.attempts,
				    m.first_attempt_at, m.lock_token, m.provider_message_id, m.created_at, m.priority
				""")
			.param("freeSlots", freeSlots)
			.param("lockTimeout", lockTimeoutSeconds)
			.param("instanceId", instanceId)
			.query((rs, row) -> new Claimed(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
					rs.getObject("template_version_id", UUID.class), rs.getString("category"), rs.getInt("priority"),
					rs.getString("locale"),
					rs.getString("to_email"), rs.getString("to_name"), strings(rs.getArray("cc")),
					strings(rs.getArray("bcc")), rs.getString("from_email"), rs.getString("from_name"),
					rs.getString("reply_to"), rs.getString("variables"), strings(rs.getArray("tags")),
					rs.getInt("attempts"), instant(rs, "first_attempt_at"), rs.getObject("lock_token", UUID.class),
					rs.getString("provider_message_id"), instant(rs, "created_at")))
			.list()
			.stream()
			// RETURNING has no order; start the most urgent first within a batch too.
			.sorted(Comparator.comparingInt(Claimed::priority))
			.toList();
	}

	/** The pinned version's content and the tenant settings a send needs (AC-12.2). */
	record SendContext(String subjectTemplate, String htmlTemplate, String textTemplate, String variablesSchema,
			String timezone, boolean tenantHeld) {
	}

	SendContext context(UUID tenantId, UUID templateVersionId) {
		return jdbc.sql("""
				SELECT v.subject_template, v.html_template, v.text_template, v.variables_schema::text AS schema,
				       t.timezone, (t.status <> 'ACTIVE' OR t.sending_paused_at IS NOT NULL) AS held
				FROM template_version v JOIN tenant t ON t.id = v.tenant_id
				WHERE v.tenant_id = :tenantId AND v.id = :versionId
				""")
			.param("tenantId", tenantId)
			.param("versionId", templateVersionId)
			.query((rs, row) -> new SendContext(rs.getString("subject_template"), rs.getString("html_template"),
					rs.getString("text_template"), rs.getString("schema"), rs.getString("timezone"),
					rs.getBoolean("held")))
			.single();
	}

	/** Suppression reason for an address hash: global, or of the message's tenant (AC-10.6). */
	Optional<String> suppression(UUID tenantId, byte[] emailHash) {
		return jdbc.sql("""
				SELECT reason FROM suppression
				WHERE email_hash = :hash AND (scope = 'GLOBAL' OR tenant_id = :tenantId)
				ORDER BY scope LIMIT 1
				""")
			.param("hash", emailHash)
			.param("tenantId", tenantId)
			.query(String.class)
			.optional();
	}

	/**
	 * SENT, fenced. Sensitive variables go in the same statement (AC-23.5); finalized_at stays
	 * null because SENT is not terminal. Returns false when the lock was lost.
	 */
	boolean markSent(Claimed message, String provider, String providerMessageId, String variables) {
		return jdbc.sql("""
				UPDATE email_message
				SET status = 'SENT', provider = :provider, provider_message_id = :providerMessageId, sent_at = now(),
				    variables = CAST(:variables AS jsonb), lock_token = NULL, lock_expires_at = NULL,
				    locked_by = NULL, updated_at = now()
				WHERE id = :id AND status = 'SENDING' AND lock_token = :token
				""")
			.param("provider", provider)
			.param("providerMessageId", providerMessageId)
			.param("variables", variables)
			.param("id", message.id())
			.param("token", message.lockToken())
			.update() == 1;
	}

	/** Terminal FAILED, fenced, with sensitive variables removed and finalized_at set. */
	boolean markFailed(Claimed message, String code, String detail, String variables) {
		return jdbc.sql("""
				UPDATE email_message
				SET status = 'FAILED', failure_code = :code, failure_detail = :detail, finalized_at = now(),
				    variables = CAST(:variables AS jsonb), attempt_log = %s,
				    lock_token = NULL, lock_expires_at = NULL, locked_by = NULL, updated_at = now()
				WHERE id = :id AND status = 'SENDING' AND lock_token = :token
				""".formatted(APPEND_LOG))
			.param("code", code)
			.param("detail", truncate(detail))
			.param("variables", variables)
			.param("id", message.id())
			.param("token", message.lockToken())
			.update() == 1;
	}

	/** Back to QUEUED for a later attempt, fenced (AC-13.1). */
	boolean markRetry(Claimed message, String code, Instant nextAttemptAt) {
		return jdbc.sql("""
				UPDATE email_message
				SET status = 'QUEUED', next_attempt_at = :next, attempt_log = %s,
				    lock_token = NULL, lock_expires_at = NULL, locked_by = NULL, updated_at = now()
				WHERE id = :id AND status = 'SENDING' AND lock_token = :token
				""".formatted(APPEND_LOG))
			.param("code", code)
			.param("next", OffsetDateTime.ofInstant(nextAttemptAt, ZoneOffset.UTC))
			.param("id", message.id())
			.param("token", message.lockToken())
			.update() == 1;
	}

	/**
	 * Hands a message of a tenant that became suspended or paused after the claim back to the
	 * queue without counting the attempt: holding is not failing (AC-02.3).
	 */
	boolean release(Claimed message) {
		return jdbc.sql("""
				UPDATE email_message
				SET status = 'QUEUED', attempts = attempts - 1, lock_token = NULL, lock_expires_at = NULL,
				    locked_by = NULL, updated_at = now()
				WHERE id = :id AND status = 'SENDING' AND lock_token = :token
				""")
			.param("id", message.id())
			.param("token", message.lockToken())
			.update() == 1;
	}

	/** After a lost lock only the history is touched, never the state (AC-12.3). */
	void logLostLock(Claimed message) {
		jdbc.sql("UPDATE email_message SET attempt_log = %s WHERE id = :id".formatted(APPEND_LOG))
			.param("code", "LOST_LOCK")
			.param("id", message.id())
			.update();
	}

	/**
	 * Reclaims SENDING messages whose lock expired (AC-11.3): back to QUEUED keeping the attempts
	 * already counted, or FAILED once they are exhausted. Must run inside a system transaction:
	 * the advisory lock lasts for that transaction, so only one instance sweeps at a time
	 * (docs/05 §5). Returns the ids that became FAILED.
	 */
	List<UUID> reclaimExpired(int maxAttempts) {
		Boolean acquired = jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtext('reclaimStuckMessages'))")
			.query(Boolean.class)
			.single();
		if (!Boolean.TRUE.equals(acquired)) {
			return List.of();
		}
		return jdbc.sql("""
				UPDATE email_message
				SET status = CASE WHEN attempts >= :maxAttempts THEN 'FAILED' ELSE 'QUEUED' END,
				    failure_code = CASE WHEN attempts >= :maxAttempts THEN 'MAX_ATTEMPTS_EXCEEDED' END,
				    finalized_at = CASE WHEN attempts >= :maxAttempts THEN now() END,
				    next_attempt_at = now(),
				    lock_token = NULL, lock_expires_at = NULL, locked_by = NULL, updated_at = now(),
				    attempt_log = %s
				WHERE status = 'SENDING' AND lock_expires_at < now()
				RETURNING id, status
				""".formatted(APPEND_LOG))
			.param("maxAttempts", maxAttempts)
			.param("code", "LOCK_EXPIRED")
			.query((rs, row) -> "FAILED".equals(rs.getString("status")) ? rs.getObject("id", UUID.class) : null)
			.list()
			.stream()
			.filter(Objects::nonNull)
			.toList();
	}

	record StoredVariables(String variables, String variablesSchema) {
	}

	StoredVariables variables(UUID messageId) {
		return jdbc.sql("""
				SELECT m.variables::text AS variables, v.variables_schema::text AS schema
				FROM email_message m JOIN template_version v ON v.tenant_id = m.tenant_id AND v.id = m.template_version_id
				WHERE m.id = :id
				""")
			.param("id", messageId)
			.query((rs, row) -> new StoredVariables(rs.getString("variables"), rs.getString("schema")))
			.single();
	}

	void replaceVariables(UUID messageId, String variables) {
		jdbc.sql("UPDATE email_message SET variables = CAST(:variables AS jsonb), updated_at = now() WHERE id = :id")
			.param("variables", variables)
			.param("id", messageId)
			.update();
	}

	static String truncate(String detail) {
		return detail == null || detail.length() <= 500 ? detail : detail.substring(0, 500);
	}

	private static List<String> strings(Array array) throws SQLException {
		return array == null ? List.of() : List.of((String[]) array.getArray());
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

}
