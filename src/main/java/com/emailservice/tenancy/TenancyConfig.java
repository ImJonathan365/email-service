package com.emailservice.tenancy;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;

/** The default transaction manager: every unqualified @Transactional runs as the current tenant (ADR-0008). */
@Configuration(proxyBeanMethods = false)
class TenancyConfig {

	@Bean
	@Primary
	PlatformTransactionManager tenantTransactionManager(DataSource dataSource) {
		return new TenantTransactionManager(dataSource);
	}

}
