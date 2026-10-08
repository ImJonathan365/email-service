package com.emailservice.sending;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Address rules of FR-09: basic RFC 5322 dot-atom syntax (no comments, no quoted local parts),
 * compared in lower case (AC-09.1, AC-10.5), and no CR/LF anywhere near a header (AC-09.3).
 */
public final class EmailAddresses {

	private static final String ATOM = "[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+";

	private static final String LABEL = "[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?";

	private static final Pattern ADDRESS = Pattern.compile(ATOM + "(\\." + ATOM + ")*@" + LABEL + "(\\." + LABEL + ")+");

	private EmailAddresses() {
	}

	public static boolean isValid(String address) {
		return address != null && address.length() <= 254 && ADDRESS.matcher(address).matches();
	}

	public static String normalized(String address) {
		return address.trim().toLowerCase(Locale.ROOT);
	}

	public static String domain(String address) {
		return normalized(address).substring(address.trim().lastIndexOf('@') + 1);
	}

	/** Exact domain or a subdomain of an entry, as for allowedLinkHosts. */
	public static boolean domainIn(String address, List<String> domains) {
		String domain = domain(address);
		return domains.stream().anyMatch(allowed -> domain.equals(allowed) || domain.endsWith("." + allowed));
	}

	public static boolean hasLineBreak(String value) {
		return value != null && (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0);
	}

}
