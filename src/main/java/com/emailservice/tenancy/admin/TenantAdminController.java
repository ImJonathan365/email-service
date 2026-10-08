package com.emailservice.tenancy.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.emailservice.common.api.ListResponse;
import com.emailservice.common.config.RoleConditions.ConditionalOnApiRole;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@ConditionalOnApiRole
@RestController
@RequestMapping("/admin/v1/tenants")
@Tag(name = "Admin: tenants")
@SecurityRequirement(name = "adminKey")
class TenantAdminController {

	private final TenantAdminService service;

	TenantAdminController(TenantAdminService service) {
		this.service = service;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Create a tenant (FR-02)")
	TenantView create(@Valid @RequestBody TenantRequests.Create body, HttpServletRequest request) {
		return service.create(body, AdminAuthenticationFilter.actor(request));
	}

	@GetMapping
	@Operation(summary = "List tenants (FR-02)")
	ListResponse<TenantView> list() {
		return ListResponse.of(service.list());
	}

	@PatchMapping("/{slug}")
	@Operation(summary = "Update limits, hosts or status; suspend with status=SUSPENDED (FR-02)")
	TenantView update(@PathVariable String slug, @Valid @RequestBody TenantRequests.Update body,
			HttpServletRequest request) {
		return service.update(slug, body, AdminAuthenticationFilter.actor(request));
	}

}
