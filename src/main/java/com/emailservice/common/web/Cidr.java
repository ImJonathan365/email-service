package com.emailservice.common.web;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * An IPv4 or IPv6 network. Parsing is literal-only (no DNS) and strict like PostgreSQL's cidr type:
 * host bits must be zero; a bare address means a single host.
 */
public final class Cidr {

	private final byte[] network;

	private final int prefixLength;

	private Cidr(byte[] network, int prefixLength) {
		this.network = network;
		this.prefixLength = prefixLength;
	}

	public static Cidr parse(String text) {
		if (text == null || text.isBlank()) {
			throw new IllegalArgumentException("CIDR is empty");
		}
		String value = text.trim();
		int slash = value.indexOf('/');
		String addressPart = slash < 0 ? value : value.substring(0, slash);
		byte[] address = literal(addressPart).getAddress();
		int maxPrefix = address.length * 8;
		int prefix;
		try {
			prefix = slash < 0 ? maxPrefix : Integer.parseInt(value.substring(slash + 1));
		}
		catch (NumberFormatException ex) {
			throw new IllegalArgumentException("Invalid CIDR prefix length: " + value);
		}
		if (prefix < 0 || prefix > maxPrefix) {
			throw new IllegalArgumentException("Invalid CIDR prefix length: " + value);
		}
		if (!Arrays.equals(address, mask(address, prefix))) {
			throw new IllegalArgumentException("CIDR has bits set to the right of the mask: " + value);
		}
		return new Cidr(address, prefix);
	}

	/** Parses an IP literal without ever resolving a host name. */
	public static InetAddress literal(String text) {
		try {
			return InetAddress.ofLiteral(text.trim());
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("Invalid IP address: " + text);
		}
	}

	public boolean contains(InetAddress address) {
		byte[] candidate = address.getAddress();
		return candidate.length == network.length && Arrays.equals(mask(candidate, prefixLength), network);
	}

	private static byte[] mask(byte[] address, int prefix) {
		byte[] masked = address.clone();
		for (int i = 0; i < masked.length; i++) {
			int bitsInByte = Math.clamp(prefix - i * 8L, 0, 8);
			masked[i] &= (byte) (0xFF << (8 - bitsInByte));
		}
		return masked;
	}

	@Override
	public String toString() {
		try {
			return InetAddress.getByAddress(network).getHostAddress() + "/" + prefixLength;
		}
		catch (UnknownHostException ex) {
			throw new IllegalStateException(ex);
		}
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof Cidr cidr && cidr.prefixLength == prefixLength && Arrays.equals(cidr.network, network);
	}

	@Override
	public int hashCode() {
		return 31 * Arrays.hashCode(network) + prefixLength;
	}

}
