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

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Resolves the Authorization header to a tenant (FR-01, FR-33). Every failure on an unknown or
 * malformed key returns the same 401, so a response never tells whether a key exists (AC-01.2).
 * An IP past AUTH_FAILURES_PER_MINUTE_PER_IP gets 429, without a database lookup, for requests
 * whose key is missing, malformed or of an unknown prefix; a known prefix is always checked in
 * full, so one misconfigured product cannot lock out the others behind the same IP (docs/08 §4).
 */
@Component
class ApiKeyAuthenticator {

	private static final Pattern BEARER = Pattern.compile("(?i)Bearer +(\\S+)");

	private static final String UNAUTHENTICATED_DETAIL = "A valid API key is required.";

	private final ApiKeyLookup lookup;

	private final AuditLog auditLog;

	private final MeterRegistry meterRegistry;

	private final KnownKeyPrefixes knownPrefixes;

	private final AuthFailuresByIp failures;

	ApiKeyAuthenticator(ApiKeyLookup lookup, AuditLog auditLog, MeterRegistry meterRegistry,
			KnownKeyPrefixes knownPrefixes, AuthFailuresByIp failures) {
		this.lookup = lookup;
		this.auditLog = auditLog;
		this.meterRegistry = meterRegistry;
		this.knownPrefixes = knownPrefixes;
		this.failures = failures;
	}

	AuthenticatedApiKey authenticate(String authorization, InetAddress clientIp, String requestId) {
		String ip = clientIp.getHostAddress();
		Optional<ApiKeyFormat.Parsed> parsed = bearerToken(authorization).flatMap(ApiKeyFormat::parse);
		boolean knownPrefix = parsed.isPresent() && knownPrefixes.mightBeActive(parsed.get().keyPrefix());
		if (!knownPrefix && failures.overThreshold(ip)) {
			meterRegistry.counter("email.auth.ip.limited").increment();
			throw ApiException.rateLimited("Too many failed authentications from this address.",
					failures.untilNextWindow());
		}
		try {
			return authenticate(authorization, parsed, clientIp, requestId);
		}
		catch (ApiException ex) {
			if (ex.code() == ErrorCode.UNAUTHENTICATED && failures.recordFailure(ip) && failures.firstTimeThisHour(ip)) {
				// One aggregated row per IP and hour, never one per failure (docs/08 §7).
				auditLog.record(lookup.jdbc(), new Actor(Actor.Type.SYSTEM, "authentication", ip, requestId),
						AuditAction.AUTHENTICATION_FAILURES_BY_IP, null, "ip", ip,
						Map.of("threshold", failures.threshold(), "window", "1 minute"));
			}
			throw ex;
		}
	}

	private AuthenticatedApiKey authenticate(String authorization, Optional<ApiKeyFormat.Parsed> parsed,
			InetAddress clientIp, String requestId) {
		if (authorization == null) {
			throw unknown("missing");
		}
		ApiKeyFormat.Parsed presented = parsed.orElseThrow(() -> unknown("malformed"));
		ApiKeyLookup.KeyRow key = lookup.findByPrefix(presented.keyPrefix())
			.filter(row -> MessageDigest.isEqual(row.keyHash(), ApiKeyFormat.hash(presented.secret())))
			// Unknown keys are counted, and aggregated per IP above, never audited one by one: that
			// would let anyone flood audit_log.
			.orElseThrow(() -> unknown("unknown_key"));

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

	/** A 401 that names no known key: counted as a metric only (email_auth_unauthenticated_total). */
	private ApiException unknown(String reason) {
		meterRegistry.counter("email.auth.unauthenticated", "reason", reason).increment();
		return unauthenticated();
	}

	private static ApiException unauthenticated() {
		return new ApiException(ErrorCode.UNAUTHENTICATED, UNAUTHENTICATED_DETAIL);
	}

}
