package com.emailservice.templates;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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

	/** Publishes one draft; its locale comes from the version itself (FR-05). */
	@Transactional
	TemplateViews.VersionPublished publish(UUID tenantId, String key, int version, Actor actor) {
		TemplateViews.StoredTemplate template = templates.lockByKey(tenantId, key)
			.orElseThrow(() -> templateNotFound(key));
		TemplateViews.StoredVersion draft = draftOf(tenantId, template, key, version);
		return publishAll(tenantId, template, key, Map.of(draft.locale(), version), false, actor).getFirst();
	}

	/**
	 * Publishes several drafts, one per locale, atomically (ADR-0020): either all become published
	 * or none does. AC-37.6 is checked on the set that is published once the operation completes.
	 */
	@Transactional
	List<TemplateViews.VersionPublished> publishJointly(UUID tenantId, String key, Map<String, Integer> versions,
			Actor actor) {
		TemplateViews.StoredTemplate template = templates.lockByKey(tenantId, key)
			.orElseThrow(() -> templateNotFound(key));
		return publishAll(tenantId, template, key, versions, true, actor);
	}

	/**
	 * Requires a schema (AC-05.5) and URL variables declared as uri (AC-04.8) on every draft, then
	 * archives the previous published version of each locale (AC-05.2, AC-37.1). Callers hold the
	 * template row lock; any failure aborts the whole transaction.
	 */
	private List<TemplateViews.VersionPublished> publishAll(UUID tenantId, TemplateViews.StoredTemplate template,
			String key, Map<String, Integer> versions, boolean joint, Actor actor) {
		Map<String, TemplateViews.StoredVersion> drafts = new TreeMap<>();
		Map<String, Set<String>> requiredAfter = new TreeMap<>();
		for (TemplateViews.StoredVersion current : templates.versions(tenantId, template.id())) {
			if ("PUBLISHED".equals(current.status()) && !versions.containsKey(current.locale())) {
				requiredAfter.put(current.locale() + " (version " + current.version() + ")",
						schema(current).requiredVariables());
			}
		}
		for (Map.Entry<String, Integer> entry : new TreeMap<>(versions).entrySet()) {
			TemplateViews.StoredVersion draft = draftOf(tenantId, template, key, entry.getValue());
			// Joint requests address errors by locale; the single-version endpoint keeps plain field names.
			String prefix = joint ? "versions." + entry.getKey() + "." : "";
			if (!draft.locale().equals(entry.getKey())) {
				throw ApiException.validation(List.of(new FieldError("versions." + entry.getKey(),
						"version " + draft.version() + " is a " + draft.locale() + " version")));
			}
			if (draft.variablesSchema() == null) {
				throw ApiException.validation(List.of(new FieldError(prefix + "variablesSchema",
						"is required to publish (version " + draft.version() + ")")));
			}
			VariablesSchema schema = schema(draft);
			List<FieldError> urlErrors = HtmlLinter.checkUrlVariables(draft.htmlTemplate(), schema);
			if (!urlErrors.isEmpty()) {
				throw new ApiException(ErrorCode.UNSAFE_TEMPLATE_CONSTRUCT,
						"Variables in URL attributes must be declared with format uri (version " + draft.version() + ").",
						urlErrors);
			}
			drafts.put(entry.getKey(), draft);
			requiredAfter.put(draft.locale() + " (version " + draft.version() + ")", schema.requiredVariables());
		}
		if (new HashSet<>(requiredAfter.values()).size() > 1) {
			List<FieldError> errors = requiredAfter.entrySet()
				.stream()
				.map(e -> new FieldError("variablesSchema.required", e.getKey() + " requires " + new TreeSet<>(e.getValue())))
				.toList();
			throw new ApiException(ErrorCode.VALIDATION_ERROR,
					"All published locales must require the same variables (AC-37.6).", errors);
		}

		List<TemplateViews.VersionPublished> result = new ArrayList<>();
		for (TemplateViews.StoredVersion draft : drafts.values()) {
			templates.archivePublished(tenantId, template.id(), draft.locale());
			TemplateViews.StoredVersion published = templates.publish(tenantId, draft.id());
			auditLog.record(templates.jdbc(), actor, AuditAction.TEMPLATE_PUBLISHED, tenantId, "template_version",
					published.id().toString(),
					Map.of("templateKey", key, "version", published.version(), "locale", published.locale()));
			result.add(new TemplateViews.VersionPublished(published.version(), published.locale(), published.status(),
					published.publishedAt()));
		}
		return result;
	}

	private VariablesSchema schema(TemplateViews.StoredVersion version) {
		return VariablesSchema.parse(schemaNode(version.variablesSchema()), "variablesSchema");
	}

	/** A version that can still change; published and archived ones are immutable (AC-05.1). */
	private TemplateViews.StoredVersion draft(UUID tenantId, String key, int version) {
		TemplateViews.StoredTemplate template = templates.lockByKey(tenantId, key)
			.orElseThrow(() -> templateNotFound(key));
		return draftOf(tenantId, template, key, version);
	}

	private TemplateViews.StoredVersion draftOf(UUID tenantId, TemplateViews.StoredTemplate template, String key,
			int version) {
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
