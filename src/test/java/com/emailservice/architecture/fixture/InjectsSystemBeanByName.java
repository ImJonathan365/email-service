package com.emailservice.architecture.fixture;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Deliberate violation used by ArchitectureTest. */
public class InjectsSystemBeanByName {

	private final JdbcClient jdbc;

	public InjectsSystemBeanByName(@Qualifier("systemJdbcClient") JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public JdbcClient jdbc() {
		return jdbc;
	}

}
