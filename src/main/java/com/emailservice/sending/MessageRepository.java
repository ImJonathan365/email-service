package com.emailservice.sending;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Acceptance-side access to tenant, suppression and email_message through the tenant DataSource
 * (email_app, RLS), always with an explicit tenant_id (NFR-08). Callers run in a tenant
 * transaction.
 */
@Repository
class MessageRepository {

	private final JdbcClient jdbc;

	MessageRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	record TenantSending(String fromEmail, String fromName, String replyTo, List<String> allowedFromDomains,
			List<String> allowedLinkHosts, String locale, String timezone, boolean paused) {
	}

	TenantSending tenant(UUID tenantId) {
		return jdbc.sql("""
				SELECT from_email, from_name, reply_to, allowed_from_domains, allowed_link_hosts, locale, timezone,
				       sending_paused_at IS NOT NULL AS paused
				FROM tenant WHERE id = :tenantId
				""")
			.param("tenantId", tenantId)
			.query((rs, row) -> new TenantSending(rs.getString("from_email"), rs.getString("from_name"),
					rs.getString("reply_to"), strings(rs.getArray("allowed_from_domains")),
					strings(rs.getArray("allowed_link_hosts")), rs.getString("locale"), rs.getString("timezone"),
					rs.getBoolean("paused")))
			.single();
	}

	/** What an idempotent repeat needs to answer with the original message (AC-08.1). */
	record Stored(UUID id, String requestHash, String status, String templateKey, int templateVersion, String locale,
			String toEmail, List<String> cc, List<String> bcc, String failureCode, String failureDetail,
			Instant createdAt) {
	}

	Optional<Stored> findByIdempotencyKey(UUID tenantId, String idempotencyKey) {
		return jdbc.sql("""
				SELECT m.id, m.request_hash, m.status, t.key AS template_key, v.version, m.locale, m.to_email, m.cc,
				       m.bcc, m.failure_code, m.failure_detail, m.created_at
				FROM email_message m
				JOIN template t ON t.tenant_id = m.tenant_id AND t.id = m.template_id
				JOIN template_version v ON v.tenant_id = m.tenant_id AND v.id = m.template_version_id
				WHERE m.tenant_id = :tenantId AND m.idempotency_key = :key
				""")
			.param("tenantId", tenantId)
			.param("key", idempotencyKey)
			.query((rs, row) -> new Stored(rs.getObject("id", UUID.class), rs.getString("request_hash"),
					rs.getString("status"), rs.getString("template_key"), rs.getInt("version"), rs.getString("locale"),
					rs.getString("to_email"), strings(rs.getArray("cc")), strings(rs.getArray("bcc")),
					rs.getString("failure_code"), rs.getString("failure_detail"), instant(rs, "created_at")))
			.optional();
	}

	/**
	 * Suppression reason per address hash (hex), global or of this tenant (ADR-0012). RLS shows
	 * email_app exactly those rows; the tenant_id filter repeats it explicitly.
	 */
	Map<String, String> suppressions(UUID tenantId, List<byte[]> hashes) {
		Map<String, String> reasons = new HashMap<>();
		jdbc.sql("""
				SELECT email_hash, reason FROM suppression
				WHERE email_hash IN (:hashes) AND (scope = 'GLOBAL' OR tenant_id = :tenantId)
				""")
			.param("hashes", hashes)
			.param("tenantId", tenantId)
			.query((rs, row) -> reasons.put(HexFormat.of().formatHex(rs.getBytes("email_hash")), rs.getString("reason")))
			.list();
		return reasons;
	}

	record NewMessage(UUID id, UUID tenantId, String idempotencyKey, String requestHash, UUID templateId,
			UUID templateVersionId, String category, int priority, String locale, String toEmail, String toName,
			List<String> cc, List<String> bcc, String fromEmail, String fromName, String replyTo, String variables,
			String status, String failureCode, String failureDetail, List<String> tags, String metadata,
			boolean trackingEnabled) {
	}

	/** Empty when a concurrent request with the same idempotency key won the insert (AC-08.4). */
	Optional<Instant> insert(NewMessage m) {
		return jdbc.sql("""
				INSERT INTO email_message (id, tenant_id, idempotency_key, request_hash, template_id,
				    template_version_id, category, priority, locale, to_email, to_name, cc, bcc, from_email, from_name,
				    reply_to, variables, status, failure_code, failure_detail, finalized_at, tags, metadata,
				    tracking_enabled, next_attempt_at)
				VALUES (:id, :tenantId, :idempotencyKey, :requestHash, :templateId, :templateVersionId, :category,
				    :priority, :locale, :toEmail, :toName, CAST(:cc AS text[]), CAST(:bcc AS text[]), :fromEmail,
				    :fromName, :replyTo, CAST(:variables AS jsonb), :status, :failureCode, :failureDetail,
				    CASE WHEN :status = 'FAILED' THEN now() END, CAST(:tags AS text[]), CAST(:metadata AS jsonb),
				    :tracking, now())
				ON CONFLICT (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL DO NOTHING
				RETURNING created_at
				""")
			.param("id", m.id())
			.param("tenantId", m.tenantId())
			.param("idempotencyKey", m.idempotencyKey())
			.param("requestHash", m.requestHash())
			.param("templateId", m.templateId())
			.param("templateVersionId", m.templateVersionId())
			.param("category", m.category())
			.param("priority", m.priority())
			.param("locale", m.locale())
			.param("toEmail", m.toEmail())
			.param("toName", m.toName())
			.param("cc", m.cc().isEmpty() ? null : m.cc().toArray(String[]::new))
			.param("bcc", m.bcc().isEmpty() ? null : m.bcc().toArray(String[]::new))
			.param("fromEmail", m.fromEmail())
			.param("fromName", m.fromName())
			.param("replyTo", m.replyTo())
			.param("variables", m.variables())
			.param("status", m.status())
			.param("failureCode", m.failureCode())
			.param("failureDetail", m.failureDetail())
			.param("tags", m.tags().isEmpty() ? null : m.tags().toArray(String[]::new))
			.param("metadata", m.metadata())
			.param("tracking", m.trackingEnabled())
			.query((rs, row) -> instant(rs, "created_at"))
			.optional();
	}

	private static List<String> strings(Array array) throws SQLException {
		return array == null ? List.of() : List.of((String[]) array.getArray());
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

}
