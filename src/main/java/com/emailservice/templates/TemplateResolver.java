package com.emailservice.templates;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.emailservice.common.api.ApiException;
import com.emailservice.common.api.ErrorCode;
import com.emailservice.templates.engine.TemplateEngine;
import com.emailservice.templates.schema.VariablesSchema;

import tools.jackson.databind.json.JsonMapper;

/**
 * Chooses the version a message is pinned to (AC-05.3, ADR-0018): an explicit version if it was
 * ever published, otherwise the published version of the requested locale, falling back to the
 * tenant's locale. Runs inside the caller's tenant transaction.
 */
@Component
public class TemplateResolver {

	private final TemplateRepository templates;

	private final JsonMapper jsonMapper;

	TemplateResolver(TemplateRepository templates, JsonMapper jsonMapper) {
		this.templates = templates;
		this.jsonMapper = jsonMapper;
	}

	/** {@code fellBack} is true when the requested locale had no published version (AC-37.3). */
	public record Resolved(UUID templateId, String templateKey, UUID versionId, int version, String locale,
			String category, boolean trackingEnabled, TemplateEngine.Sources sources, VariablesSchema schema,
			boolean fellBack) {
	}

	public Resolved resolve(UUID tenantId, String key, Integer version, String requestedLocale, String tenantLocale) {
		TemplateViews.StoredTemplate template = templates.findByKey(tenantId, key)
			.orElseThrow(() -> TemplateService.templateNotFound(key));
		if (version != null) {
			TemplateViews.StoredVersion explicit = templates.version(tenantId, template.id(), version)
				.filter(v -> !"DRAFT".equals(v.status()))
				.orElseThrow(() -> notPublished("Version " + version + " of template '" + key + "' is not published."));
			return resolved(template, explicit, false);
		}
		String locale = requestedLocale == null ? tenantLocale : requestedLocale;
		Optional<TemplateViews.StoredVersion> requested = templates.publishedVersion(tenantId, template.id(), locale);
		if (requested.isPresent()) {
			return resolved(template, requested.get(), false);
		}
		TemplateViews.StoredVersion fallback = templates.publishedVersion(tenantId, template.id(), tenantLocale)
			.orElseThrow(() -> notPublished("Template '" + key + "' has no published version."));
		return resolved(template, fallback, true);
	}

	private Resolved resolved(TemplateViews.StoredTemplate template, TemplateViews.StoredVersion version,
			boolean fellBack) {
		// Published versions always carry a schema (AC-05.5); it was validated when saved.
		VariablesSchema schema = VariablesSchema.parse(jsonMapper.readTree(version.variablesSchema()), "variablesSchema");
		return new Resolved(template.id(), template.template().key(), version.id(), version.version(), version.locale(),
				template.template().category(), template.template().trackingEnabled(),
				new TemplateEngine.Sources(version.subjectTemplate(), version.htmlTemplate(), version.textTemplate()),
				schema, fellBack);
	}

	private static ApiException notPublished(String detail) {
		return new ApiException(ErrorCode.TEMPLATE_NOT_PUBLISHED, detail);
	}

}
