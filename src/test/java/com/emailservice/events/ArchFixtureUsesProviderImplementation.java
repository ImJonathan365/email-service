package com.emailservice.events;

import com.emailservice.provider.resend.SvixWebhookVerifier;

/** ArchUnit fixture: events must not reach a provider implementation directly. */
public class ArchFixtureUsesProviderImplementation {

	SvixWebhookVerifier verifier;

}
