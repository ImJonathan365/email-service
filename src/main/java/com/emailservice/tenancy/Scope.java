package com.emailservice.tenancy;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** API key scopes (FR-33, ADR-0017). A product's deployed key only ever gets the default ones. */
public enum Scope {

	EMAILS_SEND("emails:send"),
	EMAILS_READ("emails:read"),
	TEMPLATES_WRITE("templates:write"),
	SUPPRESSIONS_WRITE("suppressions:write");

	public static final List<Scope> DEFAULTS = List.of(EMAILS_SEND, EMAILS_READ);

	private final String value;

	Scope(String value) {
		this.value = value;
	}

	public String value() {
		return value;
	}

	public static Optional<Scope> fromValue(String value) {
		return Arrays.stream(values()).filter(scope -> scope.value.equals(value)).findFirst();
	}

}
