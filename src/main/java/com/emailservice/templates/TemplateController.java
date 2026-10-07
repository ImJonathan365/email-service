package com.emailservice.templates;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.emailservice.audit.Actor;
import com.emailservice.common.api.ListResponse;
import com.emailservice.common.api.RequestIdFilter;
import com.emailservice.common.web.ClientIpResolver;
import com.emailservice.tenancy.AuthenticatedApiKey;
import com.emailservice.tenancy.RequiresScope;
import com.emailservice.tenancy.Scope;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/** /v1/templates (docs/07 §2 and §7). The tenant always comes from the API key. */
@RestController
@RequestMapping("/v1/templates")
@Tag(name = "Templates")
@SecurityRequirement(name = "apiKey")
class TemplateController {

	private final TemplateService service;

	private final TemplatePreviewService previews;

	private final ClientIpResolver clientIpResolver;

	TemplateController(TemplateService service, TemplatePreviewService previews, ClientIpResolver clientIpResolver) {
		this.service = service;
		this.previews = previews;
		this.clientIpResolver = clientIpResolver;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@RequiresScope(Scope.TEMPLATES_WRITE)
	@Operation(summary = "Create a template (FR-04)")
	TemplateViews.Template create(@Valid @RequestBody TemplateViews.CreateTemplate body, HttpServletRequest request) {
		return service.create(tenant(request), body);
	}

	@GetMapping
	@RequiresScope(Scope.EMAILS_READ)
	@Operation(summary = "List templates with their published versions (FR-04)")
	ListResponse<TemplateViews.TemplateSummary> list(HttpServletRequest request) {
		return ListResponse.of(service.list(tenant(request)));
	}

	@GetMapping("/{key}")
	@RequiresScope(Scope.EMAILS_READ)
	@Operation(summary = "Template detail with every version and its content (FR-04)")
	TemplateViews.TemplateDetail detail(@PathVariable String key, HttpServletRequest request) {
		return service.detail(tenant(request), key);
	}

	@PostMapping("/{key}/versions")
	@ResponseStatus(HttpStatus.CREATED)
	@RequiresScope(Scope.TEMPLATES_WRITE)
	@Operation(summary = "Create a draft version for one locale (FR-04, FR-37)")
	TemplateViews.VersionCreated createVersion(@PathVariable String key,
			@Valid @RequestBody TemplateViews.VersionContent body, HttpServletRequest request) {
		return service.createDraft(tenant(request), key, body);
	}

	@PutMapping("/{key}/versions/{version}")
	@RequiresScope(Scope.TEMPLATES_WRITE)
	@Operation(summary = "Replace a draft version; published versions are immutable (AC-04.3)")
	TemplateViews.VersionCreated updateVersion(@PathVariable String key, @PathVariable int version,
			@Valid @RequestBody TemplateViews.VersionContent body, HttpServletRequest request) {
		return service.updateDraft(tenant(request), key, version, body);
	}

	@DeleteMapping("/{key}/versions/{version}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@RequiresScope(Scope.TEMPLATES_WRITE)
	@Operation(summary = "Delete a draft version (AC-04.3)")
	void deleteVersion(@PathVariable String key, @PathVariable int version, HttpServletRequest request) {
		service.deleteDraft(tenant(request), key, version);
	}

	@PostMapping("/{key}/versions/{version}/publish")
	@RequiresScope(Scope.TEMPLATES_WRITE)
	@Operation(summary = "Publish a draft; it becomes immutable and archives the previous one of its locale (FR-05)")
	TemplateViews.VersionPublished publish(@PathVariable String key, @PathVariable int version,
			HttpServletRequest request) {
		AuthenticatedApiKey apiKey = AuthenticatedApiKey.from(request);
		Actor actor = new Actor(Actor.Type.API_KEY, apiKey.keyId().toString(),
				clientIpResolver.resolve(request).getHostAddress(), RequestIdFilter.current(request));
		return service.publish(apiKey.tenantId(), key, version, actor);
	}

	@PostMapping("/{key}/preview")
	@RequiresScope(Scope.TEMPLATES_WRITE)
	@Operation(summary = "Render a version with sample variables without sending anything (FR-06)")
	TemplateViews.Preview preview(@PathVariable String key, @RequestBody TemplateViews.PreviewRequest body,
			HttpServletRequest request) {
		return previews.preview(tenant(request), key, body);
	}

	private static UUID tenant(HttpServletRequest request) {
		return AuthenticatedApiKey.from(request).tenantId();
	}

}
