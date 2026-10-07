package com.emailservice.tenancy.admin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** Request and response bodies of the API key administration endpoints (FR-03, FR-33). */
final class ApiKeyViews {

	private ApiKeyViews() {
	}

	/** Scopes and CIDRs are fixed at issuance; changing them means issuing another key (AC-03.7). */
	record IssueRequest(
			@Schema(example = "production 2026") @NotBlank @Size(max = 100)
			@Pattern(regexp = TenantRequests.NO_LINE_BREAKS) String name,
			@Schema(example = "2027-10-05T00:00:00Z") @Future Instant expiresAt,
			@Schema(example = "[\"emails:send\", \"emails:read\"]") @Size(min = 1, max = 4) List<String> scopes,
			@Schema(example = "[\"10.20.0.0/16\"]") @Size(max = 20) List<String> allowedCidrs) {
	}

	/** The only response that ever contains the secret (AC-03.1). */
	record Issued(UUID id, String name, String apiKey, String keyPrefix, List<String> scopes,
			List<String> allowedCidrs, Instant expiresAt, Instant createdAt, String warning) {

		static final String WARNING = "Store this key now: it cannot be shown again.";

		@Override
		public String toString() {
			return "Issued[id=" + id + ", keyPrefix=" + keyPrefix + ", apiKey=***]";
		}

	}

	/** AC-03.2: everything about a key except its secret. */
	record Listed(UUID id, String keyPrefix, String name, String status, List<String> scopes,
			List<String> allowedCidrs, Instant createdAt, Instant expiresAt, Instant lastUsedAt, Instant revokedAt) {
	}

}
