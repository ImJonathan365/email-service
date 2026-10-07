package com.emailservice.tenancy.admin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A tenant as administrators see it (docs/07 §10). */
public record TenantView(UUID id, String slug, String name, String status, String fromEmail, String fromName,
		String replyTo, List<String> allowedFromDomains, List<String> allowedLinkHosts, String locale,
		String timezone, int rateLimitPerMinute, int dailyQuota, int retentionDays, boolean storeRenderedContent,
		Instant sendingPausedAt, String pauseReason, Instant createdAt, Instant updatedAt) {
}
