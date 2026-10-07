package com.emailservice.audit;

/** Audited actions (FR-22, docs/08 §7). Stored as text so new ones need no migration. */
public enum AuditAction {

	TENANT_CREATED,
	TENANT_UPDATED,
	TENANT_SUSPENDED,
	TENANT_REACTIVATED,
	API_KEY_ISSUED,
	API_KEY_REVOKED,
	ADMIN_ACCESS,
	ADMIN_ACCESS_DENIED,
	AUTHENTICATION_REJECTED

}
