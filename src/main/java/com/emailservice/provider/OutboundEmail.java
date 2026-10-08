package com.emailservice.provider;

import java.util.List;
import java.util.UUID;

/** A fully rendered email, ready for the provider. Built deterministically for a message (AC-12.7). */
public record OutboundEmail(UUID messageId, Address from, Address replyTo, Address to, List<String> cc,
		List<String> bcc, String subject, String html, String text, List<String> tags) {

	public OutboundEmail {
		cc = cc == null ? List.of() : List.copyOf(cc);
		bcc = bcc == null ? List.of() : List.copyOf(bcc);
		tags = tags == null ? List.of() : List.copyOf(tags);
	}

	/** An address with an optional display name. */
	public record Address(String email, String name) {
	}

	// Rendered content and addresses are personal data: never print them.
	@Override
	public String toString() {
		return "OutboundEmail[messageId=" + messageId + "]";
	}

}
