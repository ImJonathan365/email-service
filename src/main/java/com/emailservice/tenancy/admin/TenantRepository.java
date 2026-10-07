package com.emailservice.tenancy.admin;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.emailservice.common.persistence.SystemDataSource;

/** Tenant rows through email_system: administration spans tenants by definition (ADR-0008). */
@Repository
class TenantRepository {

	private static final String COLUMNS = """
			id, slug, name, status, from_email, from_name, reply_to, allowed_from_domains, allowed_link_hosts,
			locale, timezone, rate_limit_per_minute, daily_quota, retention_days, store_rendered_content,
			sending_paused_at, pause_reason, created_at, updated_at
			""";

	private final JdbcClient jdbc;

	TenantRepository(@SystemDataSource JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	JdbcClient jdbc() {
		return jdbc;
	}

	TenantView insert(UUID id, NewTenant tenant) {
		return jdbc.sql("""
				INSERT INTO tenant (id, slug, name, status, from_email, from_name, reply_to, allowed_from_domains,
				    allowed_link_hosts, locale, timezone, rate_limit_per_minute, daily_quota, retention_days,
				    store_rendered_content)
				VALUES (:id, :slug, :name, 'ACTIVE', :fromEmail, :fromName, :replyTo, CAST(:fromDomains AS text[]),
				    CAST(:linkHosts AS text[]), :locale, :timezone, :rateLimit, :dailyQuota, :retentionDays,
				    :storeRendered)
				RETURNING
				""" + COLUMNS)
			.param("id", id)
			.param("slug", tenant.slug())
			.param("name", tenant.name())
			.param("fromEmail", tenant.fromEmail())
			.param("fromName", tenant.fromName())
			.param("replyTo", tenant.replyTo())
			.param("fromDomains", tenant.allowedFromDomains().toArray(String[]::new))
			.param("linkHosts", tenant.allowedLinkHosts().toArray(String[]::new))
			.param("locale", tenant.locale())
			.param("timezone", tenant.timezone())
			.param("rateLimit", tenant.rateLimitPerMinute())
			.param("dailyQuota", tenant.dailyQuota())
			.param("retentionDays", tenant.retentionDays())
			.param("storeRendered", tenant.storeRenderedContent())
			.query(TenantRepository::map)
			.single();
	}

	List<TenantView> findAll() {
		return jdbc.sql("SELECT " + COLUMNS + " FROM tenant ORDER BY slug").query(TenantRepository::map).list();
	}

	Optional<TenantView> findBySlugForUpdate(String slug) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM tenant WHERE slug = :slug FOR UPDATE")
			.param("slug", slug)
			.query(TenantRepository::map)
			.optional();
	}

	Optional<UUID> findIdBySlug(String slug) {
		return jdbc.sql("SELECT id FROM tenant WHERE slug = :slug").param("slug", slug).query(UUID.class).optional();
	}

	TenantView update(UUID id, TenantRequests.Update change) {
		return jdbc.sql("""
				UPDATE tenant SET
				    name = coalesce(:name, name),
				    from_email = coalesce(:fromEmail, from_email),
				    from_name = coalesce(:fromName, from_name),
				    reply_to = coalesce(:replyTo, reply_to),
				    allowed_from_domains = coalesce(CAST(:fromDomains AS text[]), allowed_from_domains),
				    allowed_link_hosts = coalesce(CAST(:linkHosts AS text[]), allowed_link_hosts),
				    locale = coalesce(:locale, locale),
				    timezone = coalesce(:timezone, timezone),
				    rate_limit_per_minute = coalesce(:rateLimit, rate_limit_per_minute),
				    daily_quota = coalesce(:dailyQuota, daily_quota),
				    retention_days = coalesce(:retentionDays, retention_days),
				    store_rendered_content = coalesce(:storeRendered, store_rendered_content),
				    status = coalesce(:status, status),
				    updated_at = now()
				WHERE id = :id
				RETURNING
				""" + COLUMNS)
			.param("id", id)
			.param("name", change.name())
			.param("fromEmail", change.fromEmail())
			.param("fromName", change.fromName())
			.param("replyTo", change.replyTo())
			.param("fromDomains", toArray(change.allowedFromDomains()))
			.param("linkHosts", toArray(change.allowedLinkHosts()))
			.param("locale", change.locale())
			.param("timezone", change.timezone())
			.param("rateLimit", change.rateLimitPerMinute())
			.param("dailyQuota", change.dailyQuota())
			.param("retentionDays", change.retentionDays())
			.param("storeRendered", change.storeRenderedContent())
			.param("status", change.status())
			.query(TenantRepository::map)
			.single();
	}

	private static String[] toArray(List<String> values) {
		return values == null ? null : values.toArray(String[]::new);
	}

	private static TenantView map(ResultSet rs, int row) throws SQLException {
		return new TenantView(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("name"),
				rs.getString("status"), rs.getString("from_email"), rs.getString("from_name"),
				rs.getString("reply_to"), strings(rs.getArray("allowed_from_domains")),
				strings(rs.getArray("allowed_link_hosts")), rs.getString("locale"), rs.getString("timezone"),
				rs.getInt("rate_limit_per_minute"), rs.getInt("daily_quota"), rs.getInt("retention_days"),
				rs.getBoolean("store_rendered_content"), instant(rs, "sending_paused_at"),
				rs.getString("pause_reason"), instant(rs, "created_at"), instant(rs, "updated_at"));
	}

	static List<String> strings(Array array) throws SQLException {
		return array == null ? List.of() : List.of((String[]) array.getArray());
	}

	static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	record NewTenant(String slug, String name, String fromEmail, String fromName, String replyTo,
			List<String> allowedFromDomains, List<String> allowedLinkHosts, String locale, String timezone,
			int rateLimitPerMinute, int dailyQuota, int retentionDays, boolean storeRenderedContent) {
	}

}
