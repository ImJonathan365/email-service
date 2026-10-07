package com.emailservice.tenancy.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * The configured ADMIN_API_KEYS, kept only as SHA-256 digests. Comparing fixed-length digests in
 * constant time, against every key without short-circuit, leaks neither which key nor its length.
 */
final class AdminCredentials {

	private final List<byte[]> digests;

	AdminCredentials(List<String> keys) {
		this.digests = keys.stream().map(AdminCredentials::sha256).toList();
	}

	/** Returns a fingerprint identifying which admin key was used, for audit; never the key itself. */
	Optional<String> authenticate(String presented) {
		if (presented == null || presented.isBlank()) {
			return Optional.empty();
		}
		byte[] candidate = sha256(presented);
		byte[] matched = null;
		for (byte[] digest : digests) {
			if (MessageDigest.isEqual(digest, candidate)) {
				matched = digest;
			}
		}
		return matched == null ? Optional.empty()
				: Optional.of("admin:" + HexFormat.of().formatHex(Arrays.copyOf(matched, 6)));
	}

	private static byte[] sha256(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
