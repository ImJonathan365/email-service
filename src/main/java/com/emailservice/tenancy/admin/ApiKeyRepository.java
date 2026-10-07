package com.emailservice.tenancy.admin;

import static com.emailservice.tenancy.admin.TenantRepository.instant;
import static com.emailservice.tenancy.admin.TenantRepository.strings;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.emailservice.common.persistence.SystemDataSource;

/** api_key rows for administration, through email_system (ADR-0008). Never reads key_hash back. */
@Repository
class ApiKeyRepository {

	private static final String COLUMNS = """
			id, key_prefix, name, status, scopes, allowed_cidrs::text[] AS allowed_cidrs, created_at,
			expires_at, last_used_at, revoked_at
			""";

	private final JdbcClient jdbc;

	ApiKeyRepository(@SystemDataSource JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	JdbcClient jdbc() {
		return jdbc;
	}

	/** Empty when the prefix collided with an existing key; the caller generates another. */
	Optional<ApiKeyViews.Listed> insertUnlessPrefixTaken(UUID id, UUID tenantId, String name, String keyPrefix,
			byte[] keyHash, List<String> scopes, List<String> allowedCidrs, Instant expiresAt) {
		return jdbc.sql("""
				INSERT INTO api_key (id, tenant_id, name, key_prefix, key_hash, scopes, allowed_cidrs, status,
				    expires_at)
				VALUES (:id, :tenantId, :name, :keyPrefix, :keyHash, CAST(:scopes AS text[]),
				    CAST(:allowedCidrs AS cidr[]), 'ACTIVE', :expiresAt)
				ON CONFLICT (key_prefix) DO NOTHING
				RETURNING
				""" + COLUMNS)
			.param("id", id)
			.param("tenantId", tenantId)
			.param("name", name)
			.param("keyPrefix", keyPrefix)
			.param("keyHash", keyHash)
			.param("scopes", scopes.toArray(String[]::new))
			.param("allowedCidrs", allowedCidrs.toArray(String[]::new))
			.param("expiresAt", expiresAt == null ? null : expiresAt.atOffset(ZoneOffset.UTC))
			.query((rs, row) -> map(rs))
			.optional();
	}

	List<ApiKeyViews.Listed> findByTenant(UUID tenantId) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM api_key WHERE tenant_id = :tenantId ORDER BY created_at, id")
			.param("tenantId", tenantId)
			.query((rs, row) -> map(rs))
			.list();
	}

	/** Revokes an active key; empty if it does not exist or was already revoked. */
	Optional<Revoked> revoke(UUID id) {
		return jdbc.sql("""
				UPDATE api_key SET status = 'REVOKED', revoked_at = now()
				WHERE id = :id AND status = 'ACTIVE'
				RETURNING tenant_id, key_prefix
				""")
			.param("id", id)
			.query((rs, row) -> new Revoked(rs.getObject("tenant_id", UUID.class), rs.getString("key_prefix")))
			.optional();
	}

	boolean exists(UUID id) {
		return jdbc.sql("SELECT count(*) FROM api_key WHERE id = :id").param("id", id).query(Long.class).single() > 0;
	}

	private static ApiKeyViews.Listed map(ResultSet rs) throws SQLException {
		return new ApiKeyViews.Listed(rs.getObject("id", UUID.class), rs.getString("key_prefix"),
				rs.getString("name"), rs.getString("status"), strings(rs.getArray("scopes")),
				strings(rs.getArray("allowed_cidrs")), instant(rs, "created_at"), instant(rs, "expires_at"),
				instant(rs, "last_used_at"), instant(rs, "revoked_at"));
	}

	record Revoked(UUID tenantId, String keyPrefix) {
	}

}
