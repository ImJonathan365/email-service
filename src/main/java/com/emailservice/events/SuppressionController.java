package com.emailservice.events;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.emailservice.audit.Actor;
import com.emailservice.audit.AuditAction;
import com.emailservice.audit.AuditLog;
import com.emailservice.common.api.ApiException;
import com.emailservice.common.api.Cursor;
import com.emailservice.common.api.ErrorCode;
import com.emailservice.common.api.ListResponse;
import com.emailservice.common.api.Problem;
import com.emailservice.common.api.RequestIdFilter;
import com.emailservice.common.config.RoleConditions.ConditionalOnApiRole;
import com.emailservice.common.web.ClientIpResolver;
import com.emailservice.tenancy.AuthenticatedApiKey;
import com.emailservice.tenancy.RequiresScope;
import com.emailservice.tenancy.Scope;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * FR-18: a tenant lists the suppressions that affect it and manages its own MANUAL ones. Global
 * suppressions are visible (masked) but only an administrator removes them (AC-18.3).
 */
@ConditionalOnApiRole
@RestController
@RequestMapping("/v1/suppressions")
@Tag(name = "Suppressions")
class SuppressionController {

	private final SuppressionRepository suppressions;

	private final SuppressionHashes hashes;

	private final AuditLog auditLog;

	private final ClientIpResolver clientIpResolver;

	SuppressionController(SuppressionRepository suppressions, SuppressionHashes hashes, AuditLog auditLog,
			ClientIpResolver clientIpResolver) {
		this.suppressions = suppressions;
		this.hashes = hashes;
		this.auditLog = auditLog;
		this.clientIpResolver = clientIpResolver;
	}

	@GetMapping
	@RequiresScope(Scope.EMAILS_READ)
	@Transactional(readOnly = true)
	@Operation(summary = "List own and global suppressions; global addresses are masked (AC-18.1)")
	ListResponse<SuppressionViews.TenantView> list(
			@RequestParam(required = false) @Pattern(regexp = SuppressionViews.REASONS) String reason,
			@RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
			@RequestParam(required = false) String cursor, HttpServletRequest request) {
		List<SuppressionViews.SuppressionRow> rows = suppressions.page(tenant(request), reason, Cursor.decode(cursor),
				limit + 1);
		List<SuppressionViews.SuppressionRow> page = rows.subList(0, Math.min(limit, rows.size()));
		String next = rows.size() > limit
				? new Cursor(page.getLast().createdAt(), page.getLast().id()).encode() : null;
		return new ListResponse<>(page.stream().map(SuppressionViews.TenantView::of).toList(), next);
	}

	@PostMapping
	@RequiresScope(Scope.SUPPRESSIONS_WRITE)
	@Transactional
	@Operation(summary = "Add a MANUAL suppression for this tenant; 201 when new, 200 when it already existed (AC-18.2)")
	ResponseEntity<SuppressionViews.TenantView> add(@Valid @RequestBody SuppressionViews.Create body,
			HttpServletRequest request) {
		String email = body.email().trim();
		if (!EmailAddresses.isValid(email)) {
			throw ApiException.validation(List.of(new Problem.FieldError("email", "must be a valid email address")));
		}
		UUID tenantId = tenant(request);
		byte[] hash = hashes.hash(email);
		String note = body.note() == null || body.note().isBlank() ? null : body.note().strip();
		return suppressions.insertManual(tenantId, EmailAddresses.normalized(email), hash, note)
			.map(created -> ResponseEntity.status(HttpStatus.CREATED).body(SuppressionViews.TenantView.of(created)))
			.orElseGet(() -> ResponseEntity
				.ok(SuppressionViews.TenantView.of(suppressions.findOwn(tenantId, hash).orElseThrow())));
	}

	@DeleteMapping("/{email}")
	@RequiresScope(Scope.SUPPRESSIONS_WRITE)
	@Transactional
	@Operation(summary = "Remove this tenant's MANUAL suppression; a global one needs an administrator (AC-18.3)")
	ResponseEntity<Void> remove(@PathVariable String email, HttpServletRequest request) {
		AuthenticatedApiKey apiKey = AuthenticatedApiKey.from(request);
		byte[] hash = hashes.hash(email);
		var deleted = suppressions.deleteOwn(apiKey.tenantId(), hash);
		if (deleted.isPresent()) {
			// The audit names the suppression, never the address (AC-22.2).
			auditLog.record(suppressions.jdbc(), actor(apiKey, request), AuditAction.SUPPRESSION_DELETED,
					apiKey.tenantId(), "suppression", deleted.get().toString(), Map.of("scope", "TENANT", "reason", "MANUAL"));
			return ResponseEntity.noContent().build();
		}
		if (suppressions.globalExists(hash)) {
			throw new ApiException(ErrorCode.ADMIN_REQUIRED,
					"This address is suppressed globally; only an administrator can remove it.");
		}
		throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No suppression of this tenant for that address.");
	}

	private Actor actor(AuthenticatedApiKey apiKey, HttpServletRequest request) {
		return new Actor(Actor.Type.API_KEY, apiKey.keyId().toString(), clientIpResolver.resolve(request).getHostAddress(),
				RequestIdFilter.current(request));
	}

	private static UUID tenant(HttpServletRequest request) {
		return AuthenticatedApiKey.from(request).tenantId();
	}

}
