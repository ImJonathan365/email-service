package com.emailservice.provider.smtp;

import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import com.emailservice.provider.EmailSender;
import com.emailservice.provider.OutboundEmail;
import com.emailservice.provider.SendException;
import com.emailservice.provider.SendResult;

/**
 * SMTP to Mailpit for local development only (FR-15, ADR-0016): no webhooks, no provider id and
 * no idempotency, so it is refused in production by configuration (AC-15.3).
 */
public class SmtpEmailSender implements EmailSender {

	public static final String NAME = "smtp";

	static final String MESSAGE_ID_HEADER = "X-Email-Service-Message-Id";

	private final JavaMailSender mailSender;

	public SmtpEmailSender(JavaMailSender mailSender) {
		this.mailSender = mailSender;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public SendResult send(OutboundEmail email) throws SendException {
		MimeMessage message;
		try {
			message = build(email);
		}
		catch (MessagingException | UnsupportedEncodingException ex) {
			throw SendException.permanentFailure("INVALID_RECIPIENT", "The message could not be built for SMTP.", ex);
		}
		try {
			mailSender.send(message);
			return new SendResult(NAME, null);
		}
		catch (MailParseException | MailPreparationException ex) {
			throw SendException.permanentFailure("PROVIDER_REJECTED", "The SMTP server rejected the message.", ex);
		}
		catch (MailException ex) {
			throw classify(ex);
		}
	}

	private MimeMessage build(OutboundEmail email) throws MessagingException, UnsupportedEncodingException {
		MimeMessage message = mailSender.createMimeMessage();
		MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
		helper.setFrom(address(email.from()));
		helper.setTo(address(email.to()));
		if (email.replyTo() != null) {
			helper.setReplyTo(address(email.replyTo()));
		}
		helper.setCc(email.cc().toArray(String[]::new));
		helper.setBcc(email.bcc().toArray(String[]::new));
		helper.setSubject(email.subject());
		if (email.text() == null) {
			helper.setText(email.html(), true);
		}
		else {
			helper.setText(email.text(), email.html());
		}
		// A stable Message-ID per message lets mail clients deduplicate a retried delivery.
		message.setHeader("Message-ID", "<" + email.messageId() + "@email-service>");
		message.setHeader(MESSAGE_ID_HEADER, email.messageId().toString());
		return message;
	}

	private static InternetAddress address(OutboundEmail.Address address) throws UnsupportedEncodingException {
		return address.name() == null ? new InternetAddress(address.email(), null, StandardCharsets.UTF_8.name())
				: new InternetAddress(address.email(), address.name(), StandardCharsets.UTF_8.name());
	}

	private static SendException classify(MailException ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof SendFailedException) {
				return SendException.permanentFailure("INVALID_RECIPIENT", "The SMTP server refused a recipient.", ex);
			}
			if (cause instanceof SocketTimeoutException) {
				return SendException.transientFailure("PROVIDER_TIMEOUT", "The SMTP server timed out.", ex);
			}
			if (cause instanceof ConnectException) {
				return SendException.transientFailure("PROVIDER_UNAVAILABLE", "The SMTP server is unreachable.", ex);
			}
		}
		return SendException.transientFailure("PROVIDER_UNAVAILABLE", "The SMTP send failed.", ex);
	}

}
