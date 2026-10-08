package com.emailservice.provider;

/** Accepts everything and sends nothing (MAIL_PROVIDER=noop). Refused in production by configuration. */
public class NoopEmailSender implements EmailSender {

	@Override
	public String name() {
		return "noop";
	}

	@Override
	public SendResult send(OutboundEmail email) {
		return new SendResult(name(), "noop-" + email.messageId());
	}

}
