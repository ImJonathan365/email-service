package com.emailservice.architecture.fixture;

import javax.sql.DataSource;

import com.emailservice.common.persistence.SystemDataSource;

/** Deliberate violation used by ArchitectureTest. */
public class InjectsSystemDataSource {

	private final DataSource dataSource;

	public InjectsSystemDataSource(@SystemDataSource DataSource dataSource) {
		this.dataSource = dataSource;
	}

	public DataSource dataSource() {
		return dataSource;
	}

}
