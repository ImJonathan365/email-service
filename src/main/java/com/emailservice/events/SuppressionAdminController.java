package com.emailservice.events;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.emailservice.audit.AuditAction;
import com.emailservice.audit.AuditLog;
import com.emailservice.common.api.ApiException;
import com.emailservice.common.api.Cursor;
import com.emailservice.common.api.ErrorCode;
import com.emailservice.common.api.ListResponse;
import com.emailservice.common.config.RoleConditions.ConditionalOnApiRole;
import com.emailservice.common.persistence.SystemDataSource;
import com.emailservice.tenancy.admin.AdminRequests;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Global suppressions for administrators (ADR-0012, docs/07 §2). Removing one here does not remove
 * it from the provider's own list: the provider keeps skipping the address until it is also
 * removed in its dashboard.
 */
@ConditionalOnApiRole
@RestController
@RequestMapping("/admin/v1/suppressions")
@Tag(name = "Admin: suppressions")
@SecurityRequirement(name = "adminKey")
class SuppressionAdminController {

	private final JdbcClient jdbc;

	private final SuppressionHashes hashes;

	private final AuditLog auditLog;

	SuppressionAdminController(@SystemDataSource JdbcClient jdbc, SuppressionHashes hashes, AuditLog auditLog) {
		this.jdbc = jdbc;
		this.hashes = hashes;
		this.auditLog = auditLog;
	}

	@GetMapping
	@Transactional(transactionManager = "systemTransactionManager", readOnly = true)
	@Operation(summary = "List global suppressions with their origin (FR-18)")
	ListResponse<SuppressionViews.AdminView> list(
			@RequestParam(required = false) @Pattern(regexp = "HARD_BOUNCE|COMPLAINT|PROVIDER") String reason,
			@RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
			@RequestParam(required = false) String cursor) {
		Cursor after = Cursor.decode(cursor);
		List<SuppressionViews.AdminView> rows = jdbc.sql("""
				SELECT s.id, s.email, s.reason, s.source_tenant_id, t.slug, s.source_message_id, s.note, s.created_at
				FROM suppression s LEFT JOIN tenant t ON t.id = s.source_tenant_id
				WHERE s.scope = 'GLOBAL'
				  AND (CAST(:reason AS text) IS NULL OR s.reason = :reason)
				  AND (CAST(:afterAt AS timestamptz) IS NULL
				       OR (s.created_at, s.id) < (CAST(:afterAt AS timestamptz), CAST(:afterId AS uuid)))
				ORDER BY s.created_at DESC, s.id DESC
				LIMIT :limit
				""")
			.param("reason", reason)
			.param("afterAt", after == null ? null : Timestamp.from(after.createdAt()))
			.param("afterId", after == null ? null : after.id())
			.param("limit", limit + 1)
			.query((rs, row) -> new SuppressionViews.AdminView(rs.getObject("id", UUID.class), rs.getString("email"),
					rs.getString("reason"), rs.getObject("source_tenant_id", UUID.class), rs.getString("slug"),
					rs.getObject("source_message_id", UUID.class), rs.getString("note"),
					SuppressionRepository.instant(rs, "created_at")))
			.list();
		List<SuppressionViews.AdminView> page = rows.subList(0, Math.min(limit, rows.size()));
		String next = rows.size() > limit ? new Cursor(page.getLast().createdAt(), page.getLast().id()).encode() : null;
		return new ListResponse<>(page, next);
	}

	@DeleteMapping("/{email}")
	@Transactional("systemTransactionManager")
	@Operation(summary = "Remove a global suppression; remove it in the provider's dashboard too (ADR-0012)")
	ResponseEntity<Void> remove(@PathVariable String email, HttpServletRequest request) {
		var deleted = jdbc.sql("""
				DELETE FROM suppression WHERE scope = 'GLOBAL' AND email_hash = :hash
				RETURNING id, reason
				""")
			.param("hash", hashes.hash(email))
			.query((rs, row) -> Map.entry(rs.getObject("id", UUID.class), rs.getString("reason")))
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No global suppression for that address."));
		auditLog.record(jdbc, AdminRequests.actor(request), AuditAction.SUPPRESSION_DELETED, null, "suppression",
				deleted.getKey().toString(), Map.of("scope", "GLOBAL", "reason", deleted.getValue()));
		return ResponseEntity.noContent().build();
	}

}
