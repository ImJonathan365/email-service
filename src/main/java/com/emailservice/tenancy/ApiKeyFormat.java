package com.emailservice.tenancy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.emailservice.common.config.AppEnv;

/**
 * API key format esk_{env}_{prefix}_{secret} (AC-03.1, docs/08 §2): an 8-char Base62 prefix to
 * find the row and a 43-char Base62 secret (256 bits from a CSPRNG). Only the prefix and
 * SHA-256(secret) are stored; with that much entropy a slow password hash buys nothing.
 */
public final class ApiKeyFormat {

	private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

	private static final int PREFIX_LENGTH = 8;

	private static final int SECRET_LENGTH = 43;

	private static final Pattern TOKEN = Pattern
		.compile("(esk_(?:live|test)_[0-9A-Za-z]{" + PREFIX_LENGTH + "})_([0-9A-Za-z]{" + SECRET_LENGTH + "})");

	private ApiKeyFormat() {
	}

	/** {@code keyPrefix} is what is stored and shown, e.g. esk_live_7fA2kQ9z. */
	public record Generated(String keyPrefix, String token, byte[] secretHash) {

		@Override
		public String toString() {
			return "Generated[keyPrefix=" + keyPrefix + ", token=***]";
		}

	}

	public record Parsed(String keyPrefix, String secret) {

		@Override
		public String toString() {
			return "Parsed[keyPrefix=" + keyPrefix + ", secret=***]";
		}

	}

	public static Generated generate(AppEnv env, SecureRandom random) {
		String keyPrefix = "esk_" + (env == AppEnv.PRODUCTION ? "live" : "test") + "_" + base62(random, PREFIX_LENGTH);
		String secret = base62(random, SECRET_LENGTH);
		return new Generated(keyPrefix, keyPrefix + "_" + secret, hash(secret));
	}

	public static Optional<Parsed> parse(String token) {
		if (token == null) {
			return Optional.empty();
		}
		Matcher matcher = TOKEN.matcher(token);
		return matcher.matches() ? Optional.of(new Parsed(matcher.group(1), matcher.group(2))) : Optional.empty();
	}

	public static byte[] hash(String secret) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.US_ASCII));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String base62(SecureRandom random, int length) {
		StringBuilder value = new StringBuilder(length);
		for (int i = 0; i < length; i++) {
			value.append(BASE62.charAt(random.nextInt(BASE62.length())));
		}
		return value.toString();
	}

}
