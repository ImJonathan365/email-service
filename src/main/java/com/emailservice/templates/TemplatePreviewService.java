package com.emailservice.templates;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.emailservice.common.api.ApiException;
import com.emailservice.common.api.ErrorCode;
import com.emailservice.common.api.Problem.FieldError;
import com.emailservice.templates.engine.TemplateEngine;
import com.emailservice.templates.schema.UrlPolicy;
import com.emailservice.templates.schema.VariablesSchema;
import com.emailservice.templates.schema.VariablesValidator;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renders a version without sending anything (FR-06), with the same engine, variable checks and
 * URL rule as sending (AC-06.3), so a problem shows up here first.
 */
@Service
class TemplatePreviewService {

	static final int CLIP_WARNING_BYTES = 100 * 1024;

	static final String HTML_MAY_BE_CLIPPED = "HTML_MAY_BE_CLIPPED";

	private final TemplateRepository templates;

	private final TemplateService templateService;

	private final TemplateEngine engine;

	private final JsonMapper jsonMapper;

	TemplatePreviewService(TemplateRepository templates, TemplateService templateService, TemplateEngine engine,
			JsonMapper jsonMapper) {
		this.templates = templates;
		this.templateService = templateService;
		this.engine = engine;
		this.jsonMapper = jsonMapper;
	}

	@Transactional(readOnly = true)
	TemplateViews.Preview preview(UUID tenantId, String key, TemplateViews.PreviewRequest request) {
		TemplateViews.StoredTemplate template = templateService.find(tenantId, key);
		TemplateRepository.TenantSettings tenant = templates.tenantSettings(tenantId);
		TemplateViews.StoredVersion version = resolve(tenantId, template, request, tenant);
		JsonNode variables = request.variables() == null ? jsonMapper.createObjectNode() : request.variables();

		if (version.variablesSchema() != null) {
			VariablesSchema schema = VariablesSchema.parse(jsonMapper.readTree(version.variablesSchema()),
					"variablesSchema");
			List<FieldError> invalid = VariablesValidator.validate(schema, variables);
			if (!invalid.isEmpty()) {
				throw new ApiException(ErrorCode.TEMPLATE_VARIABLES_INVALID, "The variables do not match the template.",
						invalid);
			}
			List<FieldError> unsafe = UrlPolicy.check(schema, variables, tenant.allowedLinkHosts());
			if (!unsafe.isEmpty()) {
				throw new ApiException(ErrorCode.UNSAFE_URL, "URL variables must be https on an allowed host.", unsafe);
			}
		}

		TemplateEngine.Rendered rendered;
		try {
			rendered = engine.render(
					new TemplateEngine.Sources(version.subjectTemplate(), version.htmlTemplate(), version.textTemplate()),
					variables, version.locale(), ZoneId.of(tenant.timezone()));
		}
		catch (TemplateEngine.RenderException ex) {
			throw new ApiException(ErrorCode.TEMPLATE_VARIABLES_INVALID, ex.getMessage());
		}
		List<String> warnings = new ArrayList<>();
		if (rendered.html().getBytes(StandardCharsets.UTF_8).length > CLIP_WARNING_BYTES) {
			warnings.add(HTML_MAY_BE_CLIPPED);
		}
		return new TemplateViews.Preview(version.version(), version.locale(), version.status(), rendered.subject(),
				rendered.html(), rendered.text(), warnings);
	}

	/**
	 * An explicit version may be a draft, which is how tooling checks one before publishing.
	 * Otherwise the published version of the requested locale, falling back to the tenant's.
	 */
	private TemplateViews.StoredVersion resolve(UUID tenantId, TemplateViews.StoredTemplate template,
			TemplateViews.PreviewRequest request, TemplateRepository.TenantSettings tenant) {
		if (request.templateVersion() != null) {
			return templates.version(tenantId, template.id(), request.templateVersion())
				.orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
						"Version " + request.templateVersion() + " not found."));
		}
		String locale = request.locale() == null ? tenant.locale() : request.locale();
		return templates.publishedVersion(tenantId, template.id(), locale)
			.or(() -> templates.publishedVersion(tenantId, template.id(), tenant.locale()))
			.orElseThrow(() -> new ApiException(ErrorCode.TEMPLATE_NOT_PUBLISHED,
					"Template '" + template.template().key() + "' has no published version."));
	}

}
