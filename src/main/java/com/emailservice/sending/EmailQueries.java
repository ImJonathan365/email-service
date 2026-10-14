package com.emailservice.sending;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.emailservice.common.api.Cursor;
import com.emailservice.events.EmailAddresses;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads of FR-19 and FR-20 through email_app (RLS), always naming the tenant. The listing SQL is
 * built from the filters actually given, so each combination gets its own plan and index instead
 * of one generic plan full of "or :param is null" branches (AC-20.2).
 */
@Repository
class EmailQueries {

	private final JdbcClient jdbc;

	private final JsonMapper jsonMapper;

	EmailQueries(JdbcClient jdbc, JsonMapper jsonMapper) {
		this.jdbc = jdbc;
		this.jsonMapper = jsonMapper;
	}

	Optional<EmailViews.Detail> detail(UUID tenantId, UUID id) {
		return jdbc.sql("""
				SELECT m.id, m.status, m.category, t.key AS template_key, v.version, m.locale, m.to_email, m.cc, m.bcc,
				       m.from_email, m.tags, m.metadata::text AS metadata, m.attempts, m.provider,
				       m.provider_message_id, m.attempt_log -> -1 ->> 'code' AS last_error, m.failure_code,
				       m.failure_detail, m.created_at, m.sent_at, m.delivered_at, m.first_opened_at,
				       m.first_clicked_at, m.open_count, m.click_count, m.send_at, ten.store_rendered_content,
				       m.rendered_subject, m.rendered_html, m.rendered_text
				FROM email_message m
				JOIN template t ON t.tenant_id = m.tenant_id AND t.id = m.template_id
				JOIN template_version v ON v.tenant_id = m.tenant_id AND v.id = m.template_version_id
				JOIN tenant ten ON ten.id = m.tenant_id
				WHERE m.tenant_id = :tenantId AND m.id = :id
				""")
			.param("tenantId", tenantId)
			.param("id", id)
			.query((rs, row) -> detail(rs, events(tenantId, id)))
			.optional();
	}

	private List<EmailViews.Event> events(UUID tenantId, UUID messageId) {
		return jdbc.sql("""
				SELECT type, occurred_at FROM email_event
				WHERE tenant_id = :tenantId AND message_id = :messageId
				ORDER BY occurred_at, received_at
				""")
			.param("tenantId", tenantId)
			.param("messageId", messageId)
			.query((rs, row) -> new EmailViews.Event(rs.getString("type"), instant(rs, "occurred_at")))
			.list();
	}

	/** One page, newest first; the caller asks one row more than it returns to know if there is a next page. */
	List<EmailViews.Summary> page(UUID tenantId, EmailViews.Filter filter, Cursor after, int limit) {
		Listing listing = listing(tenantId, filter, after, limit);
		return jdbc.sql(listing.sql())
			.params(listing.params())
			.query((rs, row) -> new EmailViews.Summary(rs.getObject("id", UUID.class), rs.getString("status"),
					rs.getString("to_email"), rs.getString("template_key"), instant(rs, "created_at")))
			.list();
	}

	/** The listing statement and its named parameters; also what the AC-20.2 benchmark explains. */
	record Listing(String sql, Map<String, Object> params) {
	}

	/**
	 * Every condition is a leakproof comparison (=, <, >= on text, uuid and timestamps): under RLS,
	 * PostgreSQL only uses an index for those (V4). With a tag, the tag table drives the query and
	 * supplies the sort keys, which equal the message's.
	 */
	static Listing listing(UUID tenantId, EmailViews.Filter filter, Cursor after, int limit) {
		boolean byTag = filter.tag() != null;
		String createdAt = byTag ? "mt.created_at" : "m.created_at";
		String id = byTag ? "mt.message_id" : "m.id";
		List<String> conditions = new ArrayList<>(List.of("m.tenant_id = :tenantId"));
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("tenantId", tenantId);
		if (filter.status() != null) {
			conditions.add("m.status = :status");
			params.put("status", filter.status());
		}
		if (filter.templateKey() != null) {
			conditions.add("m.template_id = (SELECT id FROM template WHERE tenant_id = :tenantId AND key = :templateKey)");
			params.put("templateKey", filter.templateKey());
		}
		if (filter.to() != null) {
			conditions.add("m.to_email_lower = :to");
			params.put("to", EmailAddresses.normalized(filter.to()));
		}
		if (byTag) {
			conditions.add("mt.tenant_id = :tenantId AND mt.tag = :tag");
			params.put("tag", filter.tag());
		}
		if (filter.createdAfter() != null) {
			conditions.add(createdAt + " >= :createdAfter");
			params.put("createdAfter", Timestamp.from(filter.createdAfter()));
		}
		if (filter.createdBefore() != null) {
			conditions.add(createdAt + " < :createdBefore");
			params.put("createdBefore", Timestamp.from(filter.createdBefore()));
		}
		if (after != null) {
			conditions.add("(" + createdAt + ", " + id + ") < (:afterAt, :afterId)");
			params.put("afterAt", Timestamp.from(after.createdAt()));
			params.put("afterId", after.id());
		}
		params.put("limit", limit);
		String tagJoin = byTag ? "JOIN email_message_tag mt ON mt.tenant_id = m.tenant_id AND mt.message_id = m.id" : "";
		return new Listing("""
				SELECT m.id, m.status, m.to_email, t.key AS template_key, m.created_at
				FROM email_message m
				%s
				JOIN template t ON t.tenant_id = m.tenant_id AND t.id = m.template_id
				WHERE %s
				ORDER BY %s DESC, %s DESC
				LIMIT :limit
				""".formatted(tagJoin, String.join(" AND ", conditions), createdAt, id), params);
	}

	private EmailViews.Detail detail(ResultSet rs, List<EmailViews.Event> events) throws SQLException {
		boolean showRendered = rs.getBoolean("store_rendered_content");
		String metadata = rs.getString("metadata");
		JsonNode metadataNode = metadata == null ? null : jsonMapper.readTree(metadata);
		return new EmailViews.Detail(rs.getObject("id", UUID.class), rs.getString("status"), rs.getString("category"),
				rs.getString("template_key"), rs.getInt("version"), rs.getString("locale"), rs.getString("to_email"),
				strings(rs.getArray("cc")), strings(rs.getArray("bcc")), rs.getString("from_email"),
				strings(rs.getArray("tags")), metadataNode, rs.getInt("attempts"), rs.getString("provider"),
				rs.getString("provider_message_id"), rs.getString("last_error"), rs.getString("failure_code"),
				rs.getString("failure_detail"), instant(rs, "created_at"), instant(rs, "sent_at"),
				instant(rs, "delivered_at"), instant(rs, "first_opened_at"), instant(rs, "first_clicked_at"),
				rs.getInt("open_count"), rs.getInt("click_count"), instant(rs, "send_at"),
				showRendered ? rs.getString("rendered_subject") : null,
				showRendered ? rs.getString("rendered_html") : null, showRendered ? rs.getString("rendered_text") : null,
				events);
	}

	private static List<String> strings(Array array) throws SQLException {
		return array == null ? List.of() : List.of((String[]) array.getArray());
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

}
