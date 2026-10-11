package com.emailservice.events;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.emailservice.common.api.Cursor;

/**
 * Tenant-side suppressions through email_app (RLS shows the tenant's own rows and the global
 * ones, and lets it write only its own); every query also names the tenant explicitly.
 */
@Repository
class SuppressionRepository {

	private static final String COLUMNS = "id, scope, email, reason, note, created_at";

	private final JdbcClient jdbc;

	SuppressionRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	JdbcClient jdbc() {
		return jdbc;
	}

	List<SuppressionViews.SuppressionRow> page(UUID tenantId, String reason, Cursor after, int limit) {
		return jdbc.sql("""
				SELECT %s FROM suppression
				WHERE (scope = 'GLOBAL' OR tenant_id = :tenantId)
				  AND (CAST(:reason AS text) IS NULL OR reason = :reason)
				  AND (CAST(:afterAt AS timestamptz) IS NULL
				       OR (created_at, id) < (CAST(:afterAt AS timestamptz), CAST(:afterId AS uuid)))
				ORDER BY created_at DESC, id DESC
				LIMIT :limit
				""".formatted(COLUMNS))
			.param("tenantId", tenantId)
			.param("reason", reason)
			.param("afterAt", after == null ? null : Timestamp.from(after.createdAt()))
			.param("afterId", after == null ? null : after.id())
			.param("limit", limit)
			.query(SuppressionRepository::row)
			.list();
	}

	/** Inserts a MANUAL suppression; empty when the tenant already had one for this address. */
	Optional<SuppressionViews.SuppressionRow> insertManual(UUID tenantId, String email, byte[] hash, String note) {
		return jdbc.sql("""
				INSERT INTO suppression (id, scope, tenant_id, email, email_hash, reason, note)
				VALUES (:id, 'TENANT', :tenantId, :email, :hash, 'MANUAL', :note)
				ON CONFLICT (tenant_id, email_hash) WHERE scope = 'TENANT' DO NOTHING
				RETURNING %s
				""".formatted(COLUMNS))
			.param("id", UUID.randomUUID())
			.param("tenantId", tenantId)
			.param("email", email)
			.param("hash", hash)
			.param("note", note)
			.query(SuppressionRepository::row)
			.optional();
	}

	Optional<SuppressionViews.SuppressionRow> findOwn(UUID tenantId, byte[] hash) {
		return jdbc.sql("SELECT " + COLUMNS
				+ " FROM suppression WHERE scope = 'TENANT' AND tenant_id = :tenantId AND email_hash = :hash")
			.param("tenantId", tenantId)
			.param("hash", hash)
			.query(SuppressionRepository::row)
			.optional();
	}

	boolean globalExists(byte[] hash) {
		return jdbc.sql("SELECT count(*) FROM suppression WHERE scope = 'GLOBAL' AND email_hash = :hash")
			.param("hash", hash)
			.query(Long.class)
			.single() > 0;
	}

	Optional<UUID> deleteOwn(UUID tenantId, byte[] hash) {
		return jdbc.sql("""
				DELETE FROM suppression WHERE scope = 'TENANT' AND tenant_id = :tenantId AND email_hash = :hash
				RETURNING id
				""")
			.param("tenantId", tenantId)
			.param("hash", hash)
			.query(UUID.class)
			.optional();
	}

	static SuppressionViews.SuppressionRow row(ResultSet rs, int row) throws SQLException {
		return new SuppressionViews.SuppressionRow(rs.getObject("id", UUID.class), rs.getString("scope"),
				rs.getString("email"), rs.getString("reason"), rs.getString("note"), instant(rs, "created_at"));
	}

	static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

}
