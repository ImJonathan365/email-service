package com.emailservice.common.config;

import java.util.Locale;

public enum AppRole {

	// TODO(H4): register the /v1 and /webhooks controllers only with APP_ROLE=api|all; worker exposes only Actuator.
	API, WORKER, ALL, MIGRATE;

	public static AppRole parse(String value) {
		try {
			return valueOf(value.trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException("Invalid APP_ROLE '" + value + "': expected api, worker, all or migrate");
		}
	}

}
