package com.emailservice.maintenance;

import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.emailservice.audit.AuditAction;
import com.emailservice.audit.AuditLog;
import com.emailservice.common.api.ApiException;
import com.emailservice.common.api.Problem;
import com.emailservice.common.config.RoleConditions.ConditionalOnApiRole;
import com.emailservice.common.persistence.SystemDataSource;
import com.emailservice.events.EmailAddresses;
import com.emailservice.events.SuppressionHashes;
import com.emailservice.tenancy.admin.AdminRequests;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * POST /admin/v1/data-subjects/erase (FR-29), across every tenant in one transaction: messages to
 * the address and their events (their tag rows cascade), the address in other messages' cc/bcc,
 * and the plain address of its suppressions, which keep only the hash so the person is still never
 * written to. Idempotent. Audited with the hash and the reference, never the address (AC-29.2).
 */
@ConditionalOnApiRole
@RestController
@Tag(name = "Admin: data subjects")
@SecurityRequirement(name = "adminKey")
class DataSubjectErasure {

	private final JdbcClient jdbc;

	private final SuppressionHashes hashes;

	private final AuditLog auditLog;

	DataSubjectErasure(@SystemDataSource JdbcClient jdbc, SuppressionHashes hashes, AuditLog auditLog) {
		this.jdbc = jdbc;
		this.hashes = hashes;
		this.auditLog = auditLog;
	}

	record Request(@Schema(example = "ana@example.com") @NotBlank @Size(max = 254) String email,
			@Schema(example = "ARCO-2026-014") @NotBlank @Size(max = 200) @Pattern(regexp = "[^\\r\\n]*") String reference) {
	}

	record Result(Map<String, Long> deleted, long copiesRemoved, long suppressionsAnonymized) {
	}

	@PostMapping("/admin/v1/data-subjects/erase")
	@Transactional("systemTransactionManager")
	@Operation(summary = "Erase a data subject's messages, events and plain address (FR-29)")
	Result erase(@Valid @RequestBody Request body, HttpServletRequest request) {
		String email = body.email().trim();
		if (!EmailAddresses.isValid(email)) {
			throw ApiException.validation(List.of(new Problem.FieldError("email", "must be a valid email address")));
		}
		String normalized = EmailAddresses.normalized(email);
		byte[] hash = hashes.hash(email);

		Map<String, Object> counts = jdbc.sql("""
				WITH doomed AS (
				    SELECT tenant_id, id FROM email_message WHERE to_email_lower = :email),
				tags AS (
				    SELECT count(*) AS n FROM email_message_tag mt JOIN doomed d
				        ON mt.tenant_id = d.tenant_id AND mt.message_id = d.id),
				events AS (
				    DELETE FROM email_event e USING doomed d WHERE e.tenant_id = d.tenant_id AND e.message_id = d.id
				    RETURNING 1),
				messages AS (
				    DELETE FROM email_message m USING doomed d WHERE m.id = d.id RETURNING 1)
				SELECT (SELECT count(*) FROM messages) AS messages, (SELECT count(*) FROM events) AS events,
				       (SELECT n FROM tags) AS tags
				""")
			.param("email", normalized)
			.query()
			.singleRow();
		long copies = jdbc.sql("""
				UPDATE email_message
				SET cc = ARRAY(SELECT x FROM unnest(cc) AS x WHERE lower(x) <> :email),
				    bcc = ARRAY(SELECT x FROM unnest(bcc) AS x WHERE lower(x) <> :email),
				    updated_at = now()
				WHERE (cc IS NOT NULL OR bcc IS NOT NULL)
				  AND EXISTS (SELECT 1 FROM unnest(coalesce(cc, '{}') || coalesce(bcc, '{}')) AS x WHERE lower(x) = :email)
				""")
			.param("email", normalized)
			.update();
		long suppressions = jdbc.sql("UPDATE suppression SET email = NULL WHERE email_hash = :hash AND email IS NOT NULL")
			.param("hash", hash)
			.update();

		Map<String, Long> deleted = new LinkedHashMap<>();
		deleted.put("email_message", ((Number) counts.get("messages")).longValue());
		deleted.put("email_event", ((Number) counts.get("events")).longValue());
		deleted.put("email_message_tag", ((Number) counts.get("tags")).longValue());
		Result result = new Result(deleted, copies, suppressions);

		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("emailHash", HexFormat.of().formatHex(hash));
		metadata.put("reference", body.reference().strip());
		metadata.put("deleted", deleted);
		metadata.put("copiesRemoved", copies);
		metadata.put("suppressionsAnonymized", suppressions);
		auditLog.record(jdbc, AdminRequests.actor(request), AuditAction.DATA_SUBJECT_ERASED, null, "data_subject",
				HexFormat.of().formatHex(hash), metadata);
		return result;
	}

}
