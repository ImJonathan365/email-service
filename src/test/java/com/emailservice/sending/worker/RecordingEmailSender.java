package com.emailservice.sending.worker;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.emailservice.provider.EmailSender;
import com.emailservice.provider.OutboundEmail;
import com.emailservice.provider.SendException;
import com.emailservice.provider.SendResult;

/** A stub provider for worker tests: records every call and answers as programmed. Sends nothing. */
public class RecordingEmailSender implements EmailSender {

	@FunctionalInterface
	public interface Behaviour {

		SendResult send(OutboundEmail email) throws SendException;

	}

	private final List<OutboundEmail> sent = new CopyOnWriteArrayList<>();

	private volatile Behaviour behaviour = RecordingEmailSender::accept;

	static SendResult accept(OutboundEmail email) {
		return new SendResult("stub", "stub-" + email.messageId());
	}

	public void behave(Behaviour behaviour) {
		this.behaviour = behaviour;
	}

	public void reset() {
		sent.clear();
		behaviour = RecordingEmailSender::accept;
	}

	public List<OutboundEmail> sent() {
		return List.copyOf(sent);
	}

	@Override
	public String name() {
		return "stub";
	}

	@Override
	public SendResult send(OutboundEmail email) throws SendException {
		sent.add(email);
		return behaviour.send(email);
	}

}
