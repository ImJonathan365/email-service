package com.emailservice.audit;

/**
 * Who acted and from where (AC-22.2). {@code id} identifies the credential without revealing it:
 * an api_key id, or a fingerprint of the admin key.
 */
public record Actor(Type type, String id, String ip, String requestId) {

	public enum Type {

		ADMIN, API_KEY, SYSTEM

	}

}
