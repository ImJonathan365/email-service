package com.emailservice.audit;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * Appends to audit_log (FR-22). The caller passes its own JdbcClient so the entry commits or rolls
 * back with the audited action, through whichever role that caller is allowed to use.
 * Metadata must never contain secrets, email content or plain email addresses (AC-22.2).
 */
@Component
public class AuditLog {

	private final JsonMapper jsonMapper;

	public AuditLog(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	public void record(JdbcClient jdbc, Actor actor, AuditAction action, UUID tenantId, String resourceType,
			String resourceId, Map<String, ?> metadata) {
		jdbc.sql("""
				INSERT INTO audit_log (id, tenant_id, actor_type, actor_id, action, resource_type, resource_id,
				    ip, request_id, metadata)
				VALUES (:id, :tenantId, :actorType, :actorId, :action, :resourceType, :resourceId,
				    CAST(:ip AS inet), :requestId, CAST(:metadata AS jsonb))
				""")
			.param("id", UUID.randomUUID())
			.param("tenantId", tenantId)
			.param("actorType", actor.type().name())
			.param("actorId", actor.id())
			.param("action", action.name())
			.param("resourceType", resourceType)
			.param("resourceId", resourceId)
			.param("ip", actor.ip())
			.param("requestId", actor.requestId())
			.param("metadata", metadata == null || metadata.isEmpty() ? null : jsonMapper.writeValueAsString(metadata))
			.update();
	}

}
