package com.emailservice.tenancy.auth;

import java.net.InetAddress;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.emailservice.audit.Actor;
import com.emailservice.audit.AuditAction;
import com.emailservice.audit.AuditLog;
import com.emailservice.common.api.ApiException;
import com.emailservice.common.api.ErrorCode;
import com.emailservice.common.web.Cidr;
import com.emailservice.tenancy.ApiKeyFormat;
import com.emailservice.tenancy.AuthenticatedApiKey;
import com.emailservice.tenancy.Scope;

/**
 * Resolves the Authorization header to a tenant (FR-01, FR-33). Every failure on an unknown or
 * malformed key returns the same 401, so a response never tells whether a key exists (AC-01.2).
 */
@Component
class ApiKeyAuthenticator {

	private static final Pattern BEARER = Pattern.compile("(?i)Bearer +(\\S+)");

	private static final String UNAUTHENTICATED_DETAIL = "A valid API key is required.";

	private final ApiKeyLookup lookup;

	private final AuditLog auditLog;

	ApiKeyAuthenticator(ApiKeyLookup lookup, AuditLog auditLog) {
		this.lookup = lookup;
		this.auditLog = auditLog;
	}

	AuthenticatedApiKey authenticate(String authorization, InetAddress clientIp, String requestId) {
		ApiKeyFormat.Parsed presented = bearerToken(authorization).flatMap(ApiKeyFormat::parse)
			.orElseThrow(ApiKeyAuthenticator::unauthenticated);
		ApiKeyLookup.KeyRow key = lookup.findByPrefix(presented.keyPrefix())
			.filter(row -> MessageDigest.isEqual(row.keyHash(), ApiKeyFormat.hash(presented.secret())))
			// TODO(owner-decision): failures on unknown keys are not audited one by one (that would let
			// anyone flood audit_log); docs/08 §4/§7 ask for per-IP aggregation and a 401 rate limit,
			// planned with the rest of rate limiting in H7.
			.orElseThrow(ApiKeyAuthenticator::unauthenticated);

		Actor actor = new Actor(Actor.Type.API_KEY, key.id().toString(), clientIp.getHostAddress(), requestId);
		if (key.revoked()) {
			throw rejected(key, actor, "KEY_REVOKED", unauthenticated());
		}
		if (key.expired()) {
			throw rejected(key, actor, "KEY_EXPIRED", unauthenticated());
		}
		// Origin is checked before tenant status so a request from a foreign network learns nothing more.
		if (!key.allowedCidrs().isEmpty()
				&& key.allowedCidrs().stream().map(Cidr::parse).noneMatch(cidr -> cidr.contains(clientIp))) {
			throw rejected(key, actor, "IP_NOT_ALLOWED",
					new ApiException(ErrorCode.IP_NOT_ALLOWED, "The source IP is not allowed for this API key."));
		}
		if (key.tenantSuspended()) {
			throw rejected(key, actor, "TENANT_SUSPENDED",
					new ApiException(ErrorCode.TENANT_SUSPENDED, "The tenant is suspended."));
		}

		lookup.touchLastUsed(key.id());
		return new AuthenticatedApiKey(key.id(), key.tenantId(), key.tenantSlug(), scopes(key.scopes()));
	}

	private ApiException rejected(ApiKeyLookup.KeyRow key, Actor actor, String reason, ApiException response) {
		auditLog.record(lookup.jdbc(), actor, AuditAction.AUTHENTICATION_REJECTED, key.tenantId(), "api_key",
				key.id().toString(), Map.of("reason", reason));
		return response;
	}

	private static Optional<String> bearerToken(String authorization) {
		if (authorization == null) {
			return Optional.empty();
		}
		Matcher matcher = BEARER.matcher(authorization.trim());
		return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
	}

	private static Set<Scope> scopes(List<String> values) {
		return values.stream().map(Scope::fromValue).flatMap(Optional::stream).collect(Collectors.toSet());
	}

	private static ApiException unauthenticated() {
		return new ApiException(ErrorCode.UNAUTHENTICATED, UNAUTHENTICATED_DETAIL);
	}

}
