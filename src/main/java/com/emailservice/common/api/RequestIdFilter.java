package com.emailservice.common.api;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/** Accepts or assigns X-Request-Id, echoes it on every response and puts it in the log context. */
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Request-Id";

	private static final String ATTRIBUTE = RequestIdFilter.class.getName();

	// Caller-supplied ids end up in logs and audit rows, so only a short safe charset is accepted.
	private static final Pattern ACCEPTED = Pattern.compile("[A-Za-z0-9._-]{1,64}");

	public static String current(HttpServletRequest request) {
		return (String) request.getAttribute(ATTRIBUTE);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String incoming = request.getHeader(HEADER);
		String requestId = incoming != null && ACCEPTED.matcher(incoming).matches() ? incoming
				: UUID.randomUUID().toString();
		request.setAttribute(ATTRIBUTE, requestId);
		response.setHeader(HEADER, requestId);
		MDC.put("requestId", requestId);
		try {
			chain.doFilter(request, response);
		}
		finally {
			MDC.remove("requestId");
		}
	}

}
