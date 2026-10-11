package com.emailservice.tenancy.admin;

import jakarta.servlet.http.HttpServletRequest;

import com.emailservice.audit.Actor;

/** The authenticated administrator of an /admin/v1 request, for controllers outside this package. */
public final class AdminRequests {

	private AdminRequests() {
	}

	public static Actor actor(HttpServletRequest request) {
		return AdminAuthenticationFilter.actor(request);
	}

}
