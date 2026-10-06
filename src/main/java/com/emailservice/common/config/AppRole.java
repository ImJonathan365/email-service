package com.emailservice.common.config;

import java.util.Locale;

public enum AppRole {

	API, WORKER, ALL, MIGRATE;

	/** Activated only for APP_ROLE=migrate, where no runtime DataSource or web server exists. */
	public static final String MIGRATE_PROFILE = "migrate";

	public static AppRole parse(String value) {
		try {
			return valueOf(value.trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException("Invalid APP_ROLE '" + value + "': expected api, worker, all or migrate");
		}
	}

}
