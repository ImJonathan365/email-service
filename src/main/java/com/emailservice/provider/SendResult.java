package com.emailservice.provider;

/**
 * The provider accepted the email. {@code providerMessageId} correlates webhooks (FR-16); it is
 * null for SMTP, which has no provider id and no webhooks (AC-15.4).
 */
public record SendResult(String provider, String providerMessageId) {
}
