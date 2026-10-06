package com.emailservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.emailservice.support.PostgresTestDatabase;
import com.emailservice.support.PostgresTestDatabase.Role;

@SpringBootTest
class EmailServiceApplicationTests {

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", PostgresTestDatabase::jdbcUrl);
		registry.add("spring.datasource.username", () -> Role.APP.username);
		registry.add("spring.datasource.password", () -> Role.APP.password);
		registry.add("spring.flyway.user", () -> Role.OWNER.username);
		registry.add("spring.flyway.password", () -> Role.OWNER.password);
	}

	@Test
	void contextLoads() {
	}

}
