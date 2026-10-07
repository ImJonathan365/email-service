package com.emailservice.tenancy;

import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The caller of a /v1 request (FR-01). The tenant comes only from here, never from the request
 * body or parameters (docs/08 §5).
 */
public record AuthenticatedApiKey(UUID keyId, UUID tenantId, String tenantSlug, Set<Scope> scopes) {

	private static final String ATTRIBUTE = AuthenticatedApiKey.class.getName();

	public AuthenticatedApiKey {
		scopes = Set.copyOf(scopes);
	}

	public static AuthenticatedApiKey from(HttpServletRequest request) {
		AuthenticatedApiKey key = (AuthenticatedApiKey) request.getAttribute(ATTRIBUTE);
		if (key == null) {
			throw new IllegalStateException("No authenticated API key on a /v1 request");
		}
		return key;
	}

	public void bindTo(HttpServletRequest request) {
		request.setAttribute(ATTRIBUTE, this);
	}

}
