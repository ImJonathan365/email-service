package com.emailservice.sending;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.emailservice.common.api.ApiException;
import com.emailservice.common.api.ErrorCode;
import com.emailservice.templates.schema.SensitiveVariables;
import com.emailservice.templates.schema.VariablesSchema;

import tools.jackson.databind.json.JsonMapper;

/**
 * FR-26: a QUEUED message becomes CANCELED, never one the worker already claimed. The row is
 * locked first: a claim in progress holds it (FOR UPDATE SKIP LOCKED in the same short
 * transaction), so the cancellation waits, re-reads SENDING and answers 409; a cancellation that
 * locks first makes the claim skip the row, which is CANCELED by the time it is visible again.
 * Runs in the caller's tenant transaction (email_app, RLS): another tenant's id is a 404.
 */
@Component
class Cancellation {

	private record Locked(String status, String variables, String variablesSchema) {
	}

	private final JdbcClient jdbc;

	private final JsonMapper jsonMapper;

	Cancellation(JdbcClient jdbc, JsonMapper jsonMapper) {
		this.jdbc = jdbc;
		this.jsonMapper = jsonMapper;
	}

	void cancel(UUID tenantId, UUID id) {
		Locked locked = jdbc.sql("""
				SELECT m.status, m.variables::text AS variables, v.variables_schema::text AS schema
				FROM email_message m
				JOIN template_version v ON v.tenant_id = m.tenant_id AND v.id = m.template_version_id
				WHERE m.tenant_id = :tenantId AND m.id = :id
				FOR UPDATE OF m
				""")
			.param("tenantId", tenantId)
			.param("id", id)
			.query((rs, row) -> new Locked(rs.getString("status"), rs.getString("variables"), rs.getString("schema")))
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.MESSAGE_NOT_FOUND, "No message with that id."));
		if (!"QUEUED".equals(locked.status())) {
			throw new ApiException(ErrorCode.MESSAGE_NOT_CANCELABLE,
					"Only a queued message can be canceled; this one is " + locked.status() + ".");
		}
		// Terminal: the x-sensitive variables go now, the rest with the retention purge (AC-23.5).
		VariablesSchema schema = VariablesSchema.parse(jsonMapper.readTree(locked.variablesSchema()), "variablesSchema");
		String variables = jsonMapper.writeValueAsString(SensitiveVariables.strip(schema,
				jsonMapper.readTree(locked.variables())));
		jdbc.sql("""
				UPDATE email_message
				SET status = 'CANCELED', failure_code = 'CANCELED_BY_CLIENT', finalized_at = now(),
				    variables = CAST(:variables AS jsonb), updated_at = now()
				WHERE tenant_id = :tenantId AND id = :id AND status = 'QUEUED'
				""")
			.param("variables", variables)
			.param("tenantId", tenantId)
			.param("id", id)
			.update();
	}

}
