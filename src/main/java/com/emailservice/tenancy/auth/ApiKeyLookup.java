package com.emailservice.tenancy.auth;

import java.sql.Array;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.emailservice.common.persistence.SystemDataSource;

/**
 * Finds a key by its public prefix before any tenant is known, hence email_system (ADR-0008).
 * No cache: one indexed read per request is cheap at this volume and revocation is immediate.
 */
@Repository
class ApiKeyLookup {

	private final JdbcClient jdbc;

	ApiKeyLookup(@SystemDataSource JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	JdbcClient jdbc() {
		return jdbc;
	}

	Optional<KeyRow> findByPrefix(String keyPrefix) {
		return jdbc.sql("""
				SELECT k.id, k.tenant_id, k.key_hash, k.scopes, k.allowed_cidrs::text[] AS allowed_cidrs,
				       k.status, k.expires_at IS NOT NULL AND k.expires_at <= now() AS expired,
				       t.slug, t.status AS tenant_status
				FROM api_key k JOIN tenant t ON t.id = k.tenant_id
				WHERE k.key_prefix = :keyPrefix
				""")
			.param("keyPrefix", keyPrefix)
			.query((rs, row) -> new KeyRow(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
					rs.getBytes("key_hash"), strings(rs.getArray("scopes")), strings(rs.getArray("allowed_cidrs")),
					"REVOKED".equals(rs.getString("status")), rs.getBoolean("expired"), rs.getString("slug"),
					"SUSPENDED".equals(rs.getString("tenant_status"))))
			.optional();
	}

	/** AC-01.5: at most one write per key and minute, not one per request. */
	void touchLastUsed(UUID keyId) {
		jdbc.sql("""
				UPDATE api_key SET last_used_at = date_trunc('minute', now())
				WHERE id = :id AND (last_used_at IS NULL OR last_used_at < date_trunc('minute', now()))
				""").param("id", keyId).update();
	}

	private static List<String> strings(Array array) throws SQLException {
		return array == null ? List.of() : List.of((String[]) array.getArray());
	}

	record KeyRow(UUID id, UUID tenantId, byte[] keyHash, List<String> scopes, List<String> allowedCidrs,
			boolean revoked, boolean expired, String tenantSlug, boolean tenantSuspended) {
	}

}
