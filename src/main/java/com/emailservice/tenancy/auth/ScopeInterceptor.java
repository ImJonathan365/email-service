package com.emailservice.tenancy.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import com.emailservice.common.api.ApiException;
import com.emailservice.common.api.ErrorCode;
import com.emailservice.tenancy.AuthenticatedApiKey;
import com.emailservice.tenancy.RequiresScope;

/** Enforces {@link RequiresScope} on /v1 handlers (AC-01.7, AC-33.2). */
class ScopeInterceptor implements HandlerInterceptor {

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!(handler instanceof HandlerMethod method)) {
			return true;
		}
		RequiresScope required = method.getMethodAnnotation(RequiresScope.class);
		if (required == null) {
			required = method.getBeanType().getAnnotation(RequiresScope.class);
		}
		if (required == null) {
			throw new IllegalStateException("/v1 handler without @RequiresScope: " + method.getShortLogMessage());
		}
		if (!AuthenticatedApiKey.from(request).scopes().contains(required.value())) {
			throw new ApiException(ErrorCode.INSUFFICIENT_SCOPE,
					"This API key lacks the " + required.value().value() + " scope.");
		}
		return true;
	}

}
