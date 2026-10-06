package com.emailservice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.emailservice.common.persistence.SystemDataSource;
import com.emailservice.support.IntegrationTest;

class EmailServiceApplicationTests extends IntegrationTest {

	@Autowired
	JdbcClient tenantJdbc;

	@Autowired
	@SystemDataSource
	JdbcClient systemJdbc;

	@Test
	void connectsWithTheRuntimeRolesToTheMigratedSchema() {
		assertThat(systemJdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo("email_system");
		assertThat(tenantJdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo("email_app");
		assertThat(systemJdbc.sql("SELECT count(*) FROM email_message").query(Long.class).single()).isNotNull();
	}

}
