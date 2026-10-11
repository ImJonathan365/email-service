package com.emailservice.events;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.emailservice.common.persistence.SystemDataSource;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * GLOBAL suppressions written from provider events (ADR-0012): hard bounces, complaints and the
 * mirror of the provider's own list. They belong to no tenant, so they go through email_system.
 * An address that is already suppressed globally keeps its first reason.
 */
@Component
class GlobalSuppressions {

	private final JdbcClient jdbc;

	private final SuppressionHashes hashes;

	private final MeterRegistry meters;

	GlobalSuppressions(@SystemDataSource JdbcClient jdbc, SuppressionHashes hashes, MeterRegistry meters) {
		this.jdbc = jdbc;
		this.hashes = hashes;
		this.meters = meters;
	}

	/** Returns whether a new suppression was created; invalid addresses are ignored. */
	boolean add(String address, String reason, UUID sourceTenantId, UUID sourceMessageId) {
		if (!EmailAddresses.isValid(address == null ? null : address.trim())) {
			return false;
		}
		boolean created = jdbc.sql("""
				INSERT INTO suppression (id, scope, tenant_id, email, email_hash, reason, source_tenant_id,
				    source_message_id)
				VALUES (:id, 'GLOBAL', NULL, :email, :hash, :reason, :sourceTenantId, :sourceMessageId)
				ON CONFLICT (email_hash) WHERE scope = 'GLOBAL' DO NOTHING
				""")
			.param("id", UUID.randomUUID())
			.param("email", EmailAddresses.normalized(address))
			.param("hash", hashes.hash(address))
			.param("reason", reason)
			.param("sourceTenantId", sourceTenantId)
			.param("sourceMessageId", sourceMessageId)
			.update() == 1;
		if (created) {
			meters.counter("email.suppressions.created", "reason", reason).increment();
		}
		return created;
	}

	/** suppression.removed only lifts the PROVIDER mirror; bounces and complaints need an admin. */
	boolean removeProviderMirror(String address) {
		if (!EmailAddresses.isValid(address == null ? null : address.trim())) {
			return false;
		}
		return jdbc.sql("DELETE FROM suppression WHERE scope = 'GLOBAL' AND reason = 'PROVIDER' AND email_hash = :hash")
			.param("hash", hashes.hash(address))
			.update() == 1;
	}

}
