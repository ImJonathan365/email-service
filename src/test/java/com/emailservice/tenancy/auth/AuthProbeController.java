package com.emailservice.tenancy.auth;

import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.emailservice.tenancy.AuthenticatedApiKey;
import com.emailservice.tenancy.RequiresScope;
import com.emailservice.tenancy.Scope;
import com.emailservice.tenancy.TenantContext;

import io.swagger.v3.oas.annotations.Hidden;

/**
 * Test-only /v1 endpoints: H2 has no real /v1 API yet, so these exercise authentication, scopes
 * and the tenant context. Hidden from the OpenAPI contract.
 */
@Hidden
@RestController
@RequestMapping("/v1/test-probe")
class AuthProbeController {

	private final JdbcClient tenantJdbc;

	private final TransactionTemplate tenantTx;

	AuthProbeController(JdbcClient tenantJdbc, PlatformTransactionManager tenantTransactionManager) {
		this.tenantJdbc = tenantJdbc;
		this.tenantTx = new TransactionTemplate(tenantTransactionManager);
	}

	@GetMapping("/read")
	@RequiresScope(Scope.EMAILS_READ)
	Map<String, Object> read(HttpServletRequest request) {
		// Reads the tenant row the way tenant code will: inside a tenant transaction, under RLS.
		UUID visibleTenant = tenantTx.execute(status -> tenantJdbc.sql("SELECT id FROM tenant").query(UUID.class).single());
		return Map.of("tenantId", AuthenticatedApiKey.from(request).tenantId(), "contextTenantId",
				TenantContext.current().orElseThrow(), "rlsTenantId", visibleTenant);
	}

	@PostMapping("/templates")
	@RequiresScope(Scope.TEMPLATES_WRITE)
	Map<String, Object> writeTemplates() {
		return Map.of("ok", true);
	}

	@GetMapping("/unannotated")
	Map<String, Object> unannotated() {
		return Map.of("reached", true);
	}

}
