package com.emailservice.events;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/** Bodies of /v1/suppressions and /admin/v1/suppressions (docs/07 §8). */
final class SuppressionViews {

	static final String REASONS = "HARD_BOUNCE|COMPLAINT|PROVIDER|MANUAL";

	private SuppressionViews() {
	}

	record Create(@Schema(example = "bad@example.com") @NotBlank @Size(max = 254) String email,
			@Schema(example = "Requested by the user") @Size(max = 500) @Pattern(regexp = "[^\\r\\n]*") String note) {
	}

	/** What a tenant sees: its own rows in full, global rows masked and without their origin (ADR-0012). */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record TenantView(String email, String emailMasked, String scope, String reason, String note, Instant createdAt) {

		static TenantView of(SuppressionRow row) {
			boolean global = "GLOBAL".equals(row.scope());
			return new TenantView(global ? null : row.email(), global ? mask(row.email()) : null, row.scope(),
					row.reason(), global ? null : row.note(), row.createdAt());
		}

	}

	/** What an administrator sees of a global suppression, including where it came from. */
	record AdminView(UUID id, String email, String reason, UUID sourceTenantId, String sourceTenantSlug,
			UUID sourceMessageId, String note, Instant createdAt) {
	}

	record SuppressionRow(UUID id, String scope, String email, String reason, String note, Instant createdAt) {
	}

	/** An erased address (FR-29) stays hidden entirely. */
	static String mask(String email) {
		return EmailAddresses.masked(email);
	}

}
