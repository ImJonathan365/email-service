package com.emailservice.tenancy.admin;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.emailservice.common.api.ListResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/admin/v1")
@Tag(name = "Admin: API keys")
@SecurityRequirement(name = "adminKey")
class ApiKeyAdminController {

	private final ApiKeyAdminService service;

	ApiKeyAdminController(ApiKeyAdminService service) {
		this.service = service;
	}

	@PostMapping("/tenants/{slug}/api-keys")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Issue an API key; the secret is returned only in this response (FR-03, FR-33)")
	ApiKeyViews.Issued issue(@PathVariable String slug, @Valid @RequestBody ApiKeyViews.IssueRequest body,
			HttpServletRequest request) {
		return service.issue(slug, body, AdminAuthenticationFilter.actor(request));
	}

	@GetMapping("/tenants/{slug}/api-keys")
	@Operation(summary = "List a tenant's API keys, without secrets (AC-03.2)")
	ListResponse<ApiKeyViews.Listed> list(@PathVariable String slug) {
		return ListResponse.of(service.list(slug));
	}

	@DeleteMapping("/api-keys/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Revoke an API key (AC-03.4)")
	void revoke(@PathVariable UUID id, HttpServletRequest request) {
		service.revoke(id, AdminAuthenticationFilter.actor(request));
	}

}
