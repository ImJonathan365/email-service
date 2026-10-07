package com.emailservice.templates;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

/** Request and response bodies of /v1/templates (docs/07 §7). */
public final class TemplateViews {

	private TemplateViews() {
	}

	/** AC-04.1; the category cannot change later (AC-36.4). */
	public record CreateTemplate(
			@Schema(example = "password-reset") @NotBlank @Size(max = 64)
			@Pattern(regexp = "[a-z0-9]+(-[a-z0-9]+)*", message = "must be kebab-case") String key,
			@Schema(example = "Password reset") @NotBlank @Size(max = 200) String name,
			@Size(max = 1000) String description,
			@Schema(allowableValues = { "SECURITY", "TRANSACTIONAL", "NOTICE" }) @NotNull
			@Pattern(regexp = "SECURITY|TRANSACTIONAL|NOTICE") String category,
			Boolean trackingEnabled) {
	}

	/** Body to create or replace a draft version (AC-04.2). */
	public record VersionContent(
			@Schema(example = "es-CR") String locale,
			@Schema(example = "{{firstName}}, restablece tu contraseña") @NotBlank @Size(max = 500) String subjectTemplate,
			@NotBlank String htmlTemplate,
			String textTemplate,
			@Schema(type = "object") JsonNode variablesSchema) {
	}

	public record Template(String key, String name, String description, String category, boolean trackingEnabled,
			String status, Instant createdAt, Instant updatedAt) {
	}

	public record PublishedVersion(int version, String locale) {
	}

	public record TemplateSummary(String key, String name, String description, String category,
			boolean trackingEnabled, String status, List<PublishedVersion> published, Instant createdAt,
			Instant updatedAt) {
	}

	/** Detail includes each version's content, so tooling can tell whether a locale changed. */
	public record Version(int version, String locale, String status, String subjectTemplate, String htmlTemplate,
			String textTemplate, @Schema(type = "object") JsonNode variablesSchema, Instant createdAt,
			Instant publishedAt) {
	}

	public record TemplateDetail(String key, String name, String description, String category,
			boolean trackingEnabled, String status, List<Version> versions, Instant createdAt, Instant updatedAt) {
	}

	public record VersionCreated(int version, String locale, String status, Instant createdAt) {
	}

	public record VersionPublished(int version, String locale, String status, Instant publishedAt) {
	}

	/** Internal: a version row with its ids, for the service. */
	record StoredVersion(UUID id, UUID templateId, int version, String locale, String status, String subjectTemplate,
			String htmlTemplate, String textTemplate, String variablesSchema, Instant createdAt, Instant publishedAt) {
	}

	/** Internal: a template row with its id. */
	record StoredTemplate(UUID id, Template template) {
	}

}
