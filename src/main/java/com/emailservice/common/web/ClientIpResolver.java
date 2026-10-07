package com.emailservice.common.web;

import java.net.InetAddress;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The client's IP for CIDR checks and audit (AC-33.3): the socket address, unless it is a trusted
 * proxy, in which case X-Forwarded-For is walked from the right until the first untrusted hop.
 */
public class ClientIpResolver {

	private final List<Cidr> trustedProxies;

	public ClientIpResolver(List<Cidr> trustedProxies) {
		this.trustedProxies = List.copyOf(trustedProxies);
	}

	public InetAddress resolve(HttpServletRequest request) {
		InetAddress client = Cidr.literal(request.getRemoteAddr());
		String forwardedFor = request.getHeader("X-Forwarded-For");
		if (forwardedFor == null || !isTrusted(client)) {
			return client;
		}
		String[] hops = forwardedFor.split(",");
		for (int i = hops.length - 1; i >= 0; i--) {
			InetAddress hop;
			try {
				hop = Cidr.literal(hops[i]);
			}
			catch (IllegalArgumentException ex) {
				// A garbled hop could have been injected by the client; stop at the last trusted one.
				return client;
			}
			client = hop;
			if (!isTrusted(hop)) {
				return hop;
			}
		}
		return client;
	}

	private boolean isTrusted(InetAddress address) {
		return trustedProxies.stream().anyMatch(cidr -> cidr.contains(address));
	}

}
