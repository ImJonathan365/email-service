package com.emailservice.tenancy.admin;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.emailservice.audit.Actor;
import com.emailservice.audit.AuditAction;
import com.emailservice.audit.AuditLog;
import com.emailservice.common.api.ApiException;
import com.emailservice.common.api.ErrorCode;
import com.emailservice.common.api.Problem.FieldError;
import com.emailservice.common.config.AppEnv;
import com.emailservice.common.config.AppProperties;
import com.emailservice.common.web.Cidr;
import com.emailservice.tenancy.ApiKeyFormat;
import com.emailservice.tenancy.Scope;

/** Issuance, listing and revocation of tenant API keys (FR-03, FR-33), audited (AC-03.6). */
@Service
class ApiKeyAdminService {

	private static final int PREFIX_ATTEMPTS = 5;

	private final ApiKeyRepository keys;

	private final TenantRepository tenants;

	private final AuditLog auditLog;

	private final AppEnv env;

	private final SecureRandom random = new SecureRandom();

	ApiKeyAdminService(ApiKeyRepository keys, TenantRepository tenants, AuditLog auditLog, AppProperties properties) {
		this.keys = keys;
		this.tenants = tenants;
		this.auditLog = auditLog;
		this.env = properties.env();
	}

	@Transactional("systemTransactionManager")
	ApiKeyViews.Issued issue(String slug, ApiKeyViews.IssueRequest request, Actor actor) {
		UUID tenantId = tenantId(slug);
		List<FieldError> errors = new ArrayList<>();
		List<String> scopes = scopes(request.scopes(), errors);
		List<String> cidrs = cidrs(request.allowedCidrs(), errors);
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}

		for (int attempt = 0; attempt < PREFIX_ATTEMPTS; attempt++) {
			ApiKeyFormat.Generated generated = ApiKeyFormat.generate(env, random);
			Optional<ApiKeyViews.Listed> stored = keys.insertUnlessPrefixTaken(UUID.randomUUID(), tenantId,
					request.name(), generated.keyPrefix(), generated.secretHash(), scopes, cidrs, request.expiresAt());
			if (stored.isPresent()) {
				ApiKeyViews.Listed key = stored.get();
				Map<String, Object> metadata = new LinkedHashMap<>();
				metadata.put("keyPrefix", key.keyPrefix());
				metadata.put("scopes", key.scopes());
				metadata.put("allowedCidrs", key.allowedCidrs());
				metadata.put("expiresAt", key.expiresAt() == null ? null : key.expiresAt().toString());
				auditLog.record(keys.jdbc(), actor, AuditAction.API_KEY_ISSUED, tenantId, "api_key",
						key.id().toString(), metadata);
				return new ApiKeyViews.Issued(key.id(), key.name(), generated.token(), key.keyPrefix(), key.scopes(),
						key.allowedCidrs(), key.expiresAt(), key.createdAt(), ApiKeyViews.Issued.WARNING);
			}
		}
		// 62^8 prefixes make even one collision unlikely; five in a row means something is broken.
		throw new IllegalStateException("Could not generate a unique API key prefix");
	}

	@Transactional(transactionManager = "systemTransactionManager", readOnly = true)
	List<ApiKeyViews.Listed> list(String slug) {
		return keys.findByTenant(tenantId(slug));
	}

	/** Idempotent: revoking a revoked key succeeds without a second audit entry. */
	@Transactional("systemTransactionManager")
	void revoke(UUID id, Actor actor) {
		Optional<ApiKeyRepository.Revoked> revoked = keys.revoke(id);
		if (revoked.isPresent()) {
			auditLog.record(keys.jdbc(), actor, AuditAction.API_KEY_REVOKED, revoked.get().tenantId(), "api_key",
					id.toString(), Map.of("keyPrefix", revoked.get().keyPrefix()));
		}
		else if (!keys.exists(id)) {
			throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "API key not found.");
		}
	}

	private UUID tenantId(String slug) {
		return tenants.findIdBySlug(slug)
			.orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Tenant '" + slug + "' not found."));
	}

	private static List<String> scopes(List<String> requested, List<FieldError> errors) {
		if (requested == null) {
			return Scope.DEFAULTS.stream().map(Scope::value).toList();
		}
		for (int i = 0; i < requested.size(); i++) {
			if (Scope.fromValue(requested.get(i)).isEmpty()) {
				errors.add(new FieldError("scopes[" + i + "]",
						"must be one of emails:send, emails:read, templates:write, suppressions:write"));
			}
		}
		return requested.stream().distinct().toList();
	}

	private static List<String> cidrs(List<String> requested, List<FieldError> errors) {
		if (requested == null) {
			return List.of();
		}
		List<String> normalized = new ArrayList<>();
		for (int i = 0; i < requested.size(); i++) {
			try {
				normalized.add(Cidr.parse(requested.get(i)).toString());
			}
			catch (IllegalArgumentException ex) {
				errors.add(new FieldError("allowedCidrs[" + i + "]", "must be a network such as 10.20.0.0/16"));
			}
		}
		return normalized.stream().distinct().toList();
	}

}
