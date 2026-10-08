package com.emailservice.architecture.fixture;

import jakarta.mail.internet.InternetAddress;

/** Deliberate violation used by ArchitectureTest: mail classes outside the provider package. */
public class UsesMailOutsideProvider {

	public String domain(InternetAddress address) {
		return address.getAddress().substring(address.getAddress().indexOf('@') + 1);
	}

}
