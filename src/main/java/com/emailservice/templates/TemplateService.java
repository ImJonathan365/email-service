package com.emailservice.templates;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.emailservice.audit.Actor;
import com.emailservice.audit.AuditAction;
import com.emailservice.audit.AuditLog;
import com.emailservice.common.api.ApiException;
import com.emailservice.common.api.ErrorCode;
import com.emailservice.common.api.Problem.FieldError;
import com.emailservice.common.config.AppProperties;
import com.emailservice.templates.engine.TemplateEngine;
import com.emailservice.templates.lint.HtmlLinter;
import com.emailservice.templates.schema.VariablesSchema;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Templates and draft versions of the calling tenant (FR-04). Runs in tenant transactions, so RLS
 * applies on top of the explicit tenant_id in every query.
 */
@Service
class TemplateService {

	static final String DEFAULT_LOCALE = "es-CR";

	static final int MAX_HTML_BYTES = 262_144;

	private final TemplateRepository templates;

	private final TemplateEngine engine;

	private final JsonMapper jsonMapper;

	private final List<String> supportedLocales;

	private final AuditLog auditLog;

	TemplateService(TemplateRepository templates, TemplateEngine engine, JsonMapper jsonMapper,
			AppProperties properties, AuditLog auditLog) {
		this.templates = templates;
		this.auditLog = auditLog;
		this.engine = engine;
		this.jsonMapper = jsonMapper;
		this.supportedLocales = properties.tenancy().supportedLocales();
	}

	@Transactional
	TemplateViews.Template create(UUID tenantId, TemplateViews.CreateTemplate request) {
		if (Boolean.TRUE.equals(request.trackingEnabled()) && !"NOTICE".equals(request.category())) {
			throw ApiException.validation(List.of(new FieldError("trackingEnabled",
					"open and click tracking is only allowed for NOTICE templates")));
		}
		return templates.insert(tenantId, request)
			.orElseThrow(() -> new ApiException(ErrorCode.TEMPLATE_KEY_TAKEN,
					"Template key '" + request.key() + "' is in use."))
			.template();
	}

	@Transactional(readOnly = true)
	List<TemplateViews.TemplateSummary> list(UUID tenantId) {
		Map<UUID, List<TemplateViews.PublishedVersion>> published = templates.publishedVersions(tenantId)
			.stream()
			.collect(Collectors.groupingBy(TemplateRepository.PublishedRow::templateId,
					Collectors.mapping(TemplateRepository.PublishedRow::version, Collectors.toList())));
		return templates.findAll(tenantId).stream().map(stored -> {
			TemplateViews.Template t = stored.template();
			return new TemplateViews.TemplateSummary(t.key(), t.name(), t.description(), t.category(),
					t.trackingEnabled(), t.status(), published.getOrDefault(stored.id(), List.of()), t.createdAt(),
					t.updatedAt());
		}).toList();
	}

	@Transactional(readOnly = true)
	TemplateViews.TemplateDetail detail(UUID tenantId, String key) {
		TemplateViews.StoredTemplate stored = find(tenantId, key);
		TemplateViews.Template t = stored.template();
		List<TemplateViews.Version> versions = templates.versions(tenantId, stored.id())
			.stream()
			.map(v -> new TemplateViews.Version(v.version(), v.locale(), v.status(), v.subjectTemplate(),
					v.htmlTemplate(), v.textTemplate(), schemaNode(v.variablesSchema()), v.createdAt(), v.publishedAt()))
			.toList();
		return new TemplateViews.TemplateDetail(t.key(), t.name(), t.description(), t.category(), t.trackingEnabled(),
				t.status(), versions, t.createdAt(), t.updatedAt());
	}

	@Transactional
	TemplateViews.VersionCreated createDraft(UUID tenantId, String key, TemplateViews.VersionContent content) {
		TemplateViews.StoredTemplate template = templates.lockByKey(tenantId, key)
			.orElseThrow(() -> templateNotFound(key));
		Validated validated = validate(content);
		TemplateViews.StoredVersion created = templates.insertDraft(tenantId, template.id(), content,
				validated.locale(), validated.schemaJson());
		return new TemplateViews.VersionCreated(created.version(), created.locale(), created.status(),
				created.createdAt());
	}

	@Transactional
	TemplateViews.VersionCreated updateDraft(UUID tenantId, String key, int version,
			TemplateViews.VersionContent content) {
		TemplateViews.StoredVersion current = draft(tenantId, key, version);
		Validated validated = validate(content);
		TemplateViews.StoredVersion updated = templates.updateDraft(tenantId, current.id(), content,
				validated.locale(), validated.schemaJson());
		return new TemplateViews.VersionCreated(updated.version(), updated.locale(), updated.status(),
				updated.createdAt());
	}

	@Transactional
	void deleteDraft(UUID tenantId, String key, int version) {
		templates.deleteDraft(tenantId, draft(tenantId, key, version).id());
	}

	/**
	 * Publishes a draft (FR-05): schema required (AC-05.5), URL variables declared as uri
	 * (AC-04.8), the same required variables as the other published locales (AC-37.6). The
	 * previous published version of the same locale is archived (AC-05.2, AC-37.1). Audited.
	 */
	@Transactional
	TemplateViews.VersionPublished publish(UUID tenantId, String key, int version, Actor actor) {
		TemplateViews.StoredVersion draft = draft(tenantId, key, version);
		if (draft.variablesSchema() == null) {
			throw ApiException.validation(List.of(new FieldError("variablesSchema", "is required to publish")));
		}
		VariablesSchema schema = VariablesSchema.parse(schemaNode(draft.variablesSchema()), "variablesSchema");
		List<FieldError> urlErrors = HtmlLinter.checkUrlVariables(draft.htmlTemplate(), schema);
		if (!urlErrors.isEmpty()) {
			throw new ApiException(ErrorCode.UNSAFE_TEMPLATE_CONSTRUCT,
					"Variables in URL attributes must be declared with format uri.", urlErrors);
		}
		for (TemplateViews.StoredVersion other : templates.versions(tenantId, draft.templateId())) {
			if ("PUBLISHED".equals(other.status()) && !other.locale().equals(draft.locale())) {
				Set<String> otherRequired = VariablesSchema
					.parse(schemaNode(other.variablesSchema()), "variablesSchema")
					.requiredVariables();
				if (!otherRequired.equals(schema.requiredVariables())) {
					throw ApiException.validation(List.of(new FieldError("variablesSchema.required",
							"must match the published " + other.locale() + " version (version " + other.version()
									+ "): " + new TreeSet<>(otherRequired))));
				}
			}
		}

		templates.archivePublished(tenantId, draft.templateId(), draft.locale());
		TemplateViews.StoredVersion published = templates.publish(tenantId, draft.id());
		auditLog.record(templates.jdbc(), actor, AuditAction.TEMPLATE_PUBLISHED, tenantId, "template_version",
				published.id().toString(),
				Map.of("templateKey", key, "version", published.version(), "locale", published.locale()));
		return new TemplateViews.VersionPublished(published.version(), published.locale(), published.status(),
				published.publishedAt());
	}

	/** A version that can still change; published and archived ones are immutable (AC-05.1). */
	private TemplateViews.StoredVersion draft(UUID tenantId, String key, int version) {
		TemplateViews.StoredTemplate template = templates.lockByKey(tenantId, key)
			.orElseThrow(() -> templateNotFound(key));
		TemplateViews.StoredVersion stored = templates.version(tenantId, template.id(), version)
			.orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
					"Version " + version + " of template '" + key + "' not found."));
		if (!"DRAFT".equals(stored.status())) {
			throw new ApiException(ErrorCode.VERSION_IMMUTABLE,
					"Version " + version + " is " + stored.status() + " and cannot be changed.");
		}
		return stored;
	}

	TemplateViews.StoredTemplate find(UUID tenantId, String key) {
		return templates.findByKey(tenantId, key).orElseThrow(() -> templateNotFound(key));
	}

	private record Validated(String locale, String schemaJson) {
	}

	/** Field errors first, then unsafe constructs and syntax (engine), then the HTML context linter. */
	private Validated validate(TemplateViews.VersionContent content) {
		List<FieldError> errors = new ArrayList<>();
		String locale = content.locale() == null ? DEFAULT_LOCALE : content.locale();
		if (!supportedLocales.contains(locale)) {
			errors.add(new FieldError("locale", "must be one of " + supportedLocales));
		}
		if (content.htmlTemplate().getBytes(StandardCharsets.UTF_8).length > MAX_HTML_BYTES) {
			errors.add(new FieldError("htmlTemplate", "must be at most 256 KB"));
		}
		String schemaJson = null;
		JsonNode schema = content.variablesSchema();
		if (schema != null && !schema.isNull()) {
			try {
				VariablesSchema.parse(schema, "variablesSchema");
				schemaJson = jsonMapper.writeValueAsString(schema);
			}
			catch (VariablesSchema.InvalidSchemaException ex) {
				errors.addAll(ex.errors());
			}
		}
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		engine.check(new TemplateEngine.Sources(content.subjectTemplate(), content.htmlTemplate(),
				content.textTemplate()));
		List<FieldError> lint = HtmlLinter.lint(content.htmlTemplate());
		if (!lint.isEmpty()) {
			throw new ApiException(ErrorCode.UNSAFE_TEMPLATE_CONSTRUCT, "The HTML puts variables in unsafe contexts.",
					lint);
		}
		return new Validated(locale, schemaJson);
	}

	JsonNode schemaNode(String json) {
		return json == null ? null : jsonMapper.readTree(json);
	}

	static ApiException templateNotFound(String key) {
		return new ApiException(ErrorCode.TEMPLATE_NOT_FOUND, "Template '" + key + "' not found.");
	}

}
