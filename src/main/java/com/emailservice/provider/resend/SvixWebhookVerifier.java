package com.emailservice.provider.resend;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.emailservice.provider.WebhookVerifier;

/**
 * Resend signs webhooks with Svix: HMAC-SHA256 over "svix-id.svix-timestamp.body" with the
 * base64 part of the whsec_ secret, sent as space-separated "v1,signature" entries. Verified on the
 * raw bytes, in constant time, against every configured secret (two during a rotation).
 */
public class SvixWebhookVerifier implements WebhookVerifier {

	private static final String SECRET_PREFIX = "whsec_";

	private static final Pattern EVENT_ID = Pattern.compile("[A-Za-z0-9_-]{1,100}");

	private static final Pattern TIMESTAMP = Pattern.compile("[0-9]{1,12}");

	private final List<SecretKeySpec> keys;

	private final Duration tolerance;

	private final Clock clock;

	public SvixWebhookVerifier(List<String> secrets, Duration tolerance, Clock clock) {
		if (secrets.isEmpty()) {
			throw new IllegalArgumentException("At least one webhook secret is required");
		}
		this.keys = secrets.stream()
			.map(secret -> new SecretKeySpec(
					Base64.getDecoder().decode(secret.substring(secret.startsWith(SECRET_PREFIX) ? SECRET_PREFIX.length() : 0)),
					"HmacSHA256"))
			.toList();
		this.tolerance = tolerance;
		this.clock = clock;
	}

	@Override
	public String provider() {
		return ResendEmailSender.NAME;
	}

	@Override
	public Verified verify(Function<String, String> header, byte[] body) throws Rejected {
		String id = header.apply("svix-id");
		String timestamp = header.apply("svix-timestamp");
		String signatures = header.apply("svix-signature");
		if (id == null || timestamp == null || signatures == null || !EVENT_ID.matcher(id).matches()
				|| !TIMESTAMP.matcher(timestamp).matches()) {
			throw new Rejected(Rejected.Reason.MISSING_HEADERS);
		}
		// Signature first: an unauthenticated caller learns nothing about our clock.
		byte[] signedContent = signedContent(id, timestamp, body);
		if (!matchesAny(signedContent, signatures)) {
			throw new Rejected(Rejected.Reason.INVALID_SIGNATURE);
		}
		Instant signedAt = Instant.ofEpochSecond(Long.parseLong(timestamp));
		if (Duration.between(signedAt, clock.instant()).abs().compareTo(tolerance) > 0) {
			throw new Rejected(Rejected.Reason.OUTSIDE_TOLERANCE);
		}
		return new Verified(id, signedAt);
	}

	private static byte[] signedContent(String id, String timestamp, byte[] body) {
		byte[] prefix = (id + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8);
		byte[] content = new byte[prefix.length + body.length];
		System.arraycopy(prefix, 0, content, 0, prefix.length);
		System.arraycopy(body, 0, content, prefix.length, body.length);
		return content;
	}

	private boolean matchesAny(byte[] signedContent, String header) {
		List<byte[]> expected = keys.stream().map(key -> sign(key, signedContent)).toList();
		boolean match = false;
		for (String entry : header.trim().split(" +")) {
			int comma = entry.indexOf(',');
			if (comma < 0 || !"v1".equals(entry.substring(0, comma))) {
				continue;
			}
			byte[] received;
			try {
				received = Base64.getDecoder().decode(entry.substring(comma + 1));
			}
			catch (IllegalArgumentException ex) {
				continue;
			}
			for (byte[] candidate : expected) {
				match |= MessageDigest.isEqual(candidate, received);
			}
		}
		return match;
	}

	private static byte[] sign(SecretKeySpec key, byte[] content) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(key);
			return mac.doFinal(content);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	@Override
	public String toString() {
		return "SvixWebhookVerifier[secrets=" + keys.size() + " x ***, tolerance=" + tolerance + "]";
	}

}
