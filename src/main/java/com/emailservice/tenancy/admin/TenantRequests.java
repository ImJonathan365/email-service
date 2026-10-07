package com.emailservice.tenancy.admin;

import java.util.List;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** Request bodies of /admin/v1/tenants. Values that end up in mail headers reject CR/LF. */
final class TenantRequests {

	static final String NO_LINE_BREAKS = "[^\\r\\n]*";

	private TenantRequests() {
	}

	record Create(
			@Schema(example = "colmena") @NotBlank @Pattern(regexp = "[a-z0-9-]{2,40}",
					message = "must be 2-40 lowercase letters, digits or hyphens") String slug,
			@Schema(example = "Colmena") @NotBlank @Size(max = 200) @Pattern(regexp = NO_LINE_BREAKS) String name,
			@Schema(example = "no-reply@colmena.cr") @NotBlank @Email @Size(max = 254)
			@Pattern(regexp = NO_LINE_BREAKS) String fromEmail,
			@Schema(example = "Colmena") @NotBlank @Size(max = 80) @Pattern(regexp = NO_LINE_BREAKS) String fromName,
			@Email @Size(max = 254) @Pattern(regexp = NO_LINE_BREAKS) String replyTo,
			@Schema(example = "[\"colmena.cr\"]") @Size(max = 20) List<String> allowedFromDomains,
			@Schema(example = "[\"colmena.cr\", \"app.colmena.cr\"]") @Size(max = 50) List<String> allowedLinkHosts,
			@Schema(example = "es-CR") String locale,
			@Schema(example = "America/Costa_Rica") String timezone,
			@Positive Integer rateLimitPerMinute,
			@Positive Integer dailyQuota,
			@Min(7) @Max(400) Integer retentionDays,
			Boolean storeRenderedContent) {
	}

	/** Partial update: a null or absent field keeps its current value. */
	record Update(
			@Size(min = 1, max = 200) @Pattern(regexp = NO_LINE_BREAKS) String name,
			@Email @Size(min = 3, max = 254) @Pattern(regexp = NO_LINE_BREAKS) String fromEmail,
			@Size(min = 1, max = 80) @Pattern(regexp = NO_LINE_BREAKS) String fromName,
			@Email @Size(min = 3, max = 254) @Pattern(regexp = NO_LINE_BREAKS) String replyTo,
			@Size(max = 20) List<String> allowedFromDomains,
			@Size(max = 50) List<String> allowedLinkHosts,
			String locale,
			String timezone,
			@Positive Integer rateLimitPerMinute,
			@Positive Integer dailyQuota,
			@Min(7) @Max(400) Integer retentionDays,
			Boolean storeRenderedContent,
			@Schema(allowableValues = { "ACTIVE", "SUSPENDED" }) @Pattern(regexp = "ACTIVE|SUSPENDED") String status) {
	}

}
