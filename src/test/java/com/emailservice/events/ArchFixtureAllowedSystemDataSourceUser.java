package com.emailservice.events;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.emailservice.common.persistence.SystemDataSource;

/** Allowed usage (events may use email_system) for ArchitectureTest. */
public class ArchFixtureAllowedSystemDataSourceUser {

	private final JdbcClient jdbc;

	private final JdbcClient byName;

	public ArchFixtureAllowedSystemDataSourceUser(@SystemDataSource JdbcClient jdbc,
			@Qualifier("systemJdbcClient") JdbcClient byName) {
		this.jdbc = jdbc;
		this.byName = byName;
	}

	public JdbcClient jdbc() {
		return byName != null ? byName : jdbc;
	}

}
