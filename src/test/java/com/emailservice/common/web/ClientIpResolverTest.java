package com.emailservice.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** AC-33.3: X-Forwarded-For is only believed when it comes from a trusted proxy. */
class ClientIpResolverTest {

	final ClientIpResolver resolver = new ClientIpResolver(List.of(Cidr.parse("10.0.0.0/8")));

	@Test
	void untrustedPeerIsTheClientEvenWithForwardedFor() {
		assertThat(resolve("203.0.113.7", "198.51.100.1")).isEqualTo("203.0.113.7");
	}

	@Test
	void trustedProxyForwardsTheClient() {
		assertThat(resolve("10.0.0.5", "198.51.100.1")).isEqualTo("198.51.100.1");
	}

	@Test
	void spoofedLeftmostHopIsIgnored() {
		// The client sent "1.2.3.4" itself; the trusted proxy appended the real peer.
		assertThat(resolve("10.0.0.5", "1.2.3.4, 198.51.100.1")).isEqualTo("198.51.100.1");
	}

	@Test
	void chainOfTrustedProxiesIsSkipped() {
		assertThat(resolve("10.0.0.5", "198.51.100.1, 10.0.0.9")).isEqualTo("198.51.100.1");
	}

	@Test
	void garbledHopStopsAtTheLastTrustedAddress() {
		assertThat(resolve("10.0.0.5", "198.51.100.1, not-an-ip")).isEqualTo("10.0.0.5");
	}

	@Test
	void noForwardedForMeansThePeer() {
		assertThat(resolve("10.0.0.5", null)).isEqualTo("10.0.0.5");
	}

	private String resolve(String remoteAddr, String forwardedFor) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr(remoteAddr);
		if (forwardedFor != null) {
			request.addHeader("X-Forwarded-For", forwardedFor);
		}
		return resolver.resolve(request).getHostAddress();
	}

}
