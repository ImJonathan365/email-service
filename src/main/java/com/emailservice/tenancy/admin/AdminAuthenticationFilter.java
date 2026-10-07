package com.emailservice.tenancy.admin;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.filter.OncePerRequestFilter;

import com.emailservice.audit.Actor;
import com.emailservice.audit.AuditAction;
import com.emailservice.audit.AuditLog;
import com.emailservice.common.api.ApiException;
import com.emailservice.common.api.ErrorCode;
import com.emailservice.common.api.ProblemWriter;
import com.emailservice.common.api.RequestIdFilter;
import com.emailservice.common.web.ClientIpResolver;

/**
 * Guards /admin/v1/** with X-Admin-Key (FR-02). A tenant API key is never accepted here
 * (AC-02.4). Every access is audited (AC-22.1): mutations by the service with their specific
 * action, reads and denials here.
 */
class AdminAuthenticationFilter extends OncePerRequestFilter {

	static final String HEADER = "X-Admin-Key";

	private static final String ACTOR_ATTRIBUTE = AdminAuthenticationFilter.class.getName() + ".actor";

	private final AdminCredentials credentials;

	private final ClientIpResolver clientIpResolver;

	private final ProblemWriter problems;

	private final AuditLog auditLog;

	private final JdbcClient systemJdbc;

	AdminAuthenticationFilter(AdminCredentials credentials, ClientIpResolver clientIpResolver, ProblemWriter problems,
			AuditLog auditLog, JdbcClient systemJdbc) {
		this.credentials = credentials;
		this.clientIpResolver = clientIpResolver;
		this.problems = problems;
		this.auditLog = auditLog;
		this.systemJdbc = systemJdbc;
	}

	static Actor actor(HttpServletRequest request) {
		Actor actor = (Actor) request.getAttribute(ACTOR_ATTRIBUTE);
		if (actor == null) {
			throw new IllegalStateException("Admin request reached a controller without authentication");
		}
		return actor;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String ip = clientIpResolver.resolve(request).getHostAddress();
		String requestId = RequestIdFilter.current(request);
		Map<String, String> access = Map.of("method", request.getMethod(), "path", redactedPath(request));

		var adminId = credentials.authenticate(request.getHeader(HEADER));
		if (adminId.isEmpty()) {
			auditLog.record(systemJdbc, new Actor(Actor.Type.ADMIN, null, ip, requestId),
					AuditAction.ADMIN_ACCESS_DENIED, null, null, null, access);
			problems.write(request, response,
					new ApiException(ErrorCode.ADMIN_REQUIRED, "A valid X-Admin-Key header is required."));
			return;
		}

		Actor actor = new Actor(Actor.Type.ADMIN, adminId.get(), ip, requestId);
		request.setAttribute(ACTOR_ATTRIBUTE, actor);
		if ("GET".equals(request.getMethod())) {
			auditLog.record(systemJdbc, actor, AuditAction.ADMIN_ACCESS, null, null, null, access);
		}
		chain.doFilter(request, response);
	}

	// Some admin routes carry an email address in the path; audit rows must never store one (AC-22.2).
	private static String redactedPath(HttpServletRequest request) {
		return request.getRequestURI().replaceAll("[^/]*@[^/]*", "{email}");
	}

}
