package com.emailservice.tenancy.auth;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import com.emailservice.common.api.ApiException;
import com.emailservice.common.api.ProblemWriter;
import com.emailservice.common.api.RequestIdFilter;
import com.emailservice.common.web.ClientIpResolver;
import com.emailservice.tenancy.AuthenticatedApiKey;
import com.emailservice.tenancy.TenantContext;

/**
 * Authenticates every /v1 request and runs the rest of it inside the key's tenant context, so
 * each tenant transaction sets app.tenant_id for RLS (AC-01.1).
 */
class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

	private final ApiKeyAuthenticator authenticator;

	private final ClientIpResolver clientIpResolver;

	private final ProblemWriter problems;

	ApiKeyAuthenticationFilter(ApiKeyAuthenticator authenticator, ClientIpResolver clientIpResolver,
			ProblemWriter problems) {
		this.authenticator = authenticator;
		this.clientIpResolver = clientIpResolver;
		this.problems = problems;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		AuthenticatedApiKey key;
		try {
			key = authenticator.authenticate(request.getHeader("Authorization"), clientIpResolver.resolve(request),
					RequestIdFilter.current(request));
		}
		catch (ApiException ex) {
			problems.write(request, response, ex);
			return;
		}

		key.bindTo(request);
		MDC.put("tenantSlug", key.tenantSlug());
		try {
			TenantContext.call(key.tenantId(), () -> {
				chain.doFilter(request, response);
				return null;
			});
		}
		catch (IOException | ServletException | RuntimeException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new ServletException(ex);
		}
		finally {
			MDC.remove("tenantSlug");
		}
	}

}
