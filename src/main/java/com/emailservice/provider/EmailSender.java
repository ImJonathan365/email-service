package com.emailservice.provider;

/**
 * Port to the email provider (FR-14). Implementations live in this package and translate their
 * SDK or protocol types into {@link SendResult} and {@link SendException}; nothing else crosses.
 */
public interface EmailSender {

	/** Provider name stored in email_message.provider. */
	String name();

	/**
	 * Sends one email. {@code email.messageId()} is the idempotency key for providers that support
	 * one (ADR-0010), so a retry of the same message never produces a second email.
	 */
	SendResult send(OutboundEmail email) throws SendException;

}
