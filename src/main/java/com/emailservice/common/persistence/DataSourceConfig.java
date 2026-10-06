package com.emailservice.common.persistence;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import com.emailservice.common.config.AppProperties;
import com.emailservice.common.config.AppRole;
import com.emailservice.tenancy.TenantTransactionManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Two pools (ADR-0008): the primary one runs as email_app under RLS; the system one runs as
 * email_system and is only reachable through the {@link SystemDataSource} qualifier.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!" + AppRole.MIGRATE_PROFILE)
public class DataSourceConfig {

	private static final int TENANT_POOL_SIZE = 10;

	private static final int SYSTEM_POOL_SIZE = 4;

	@Bean
	@Primary
	HikariDataSource tenantDataSource(AppProperties properties) {
		return pool("tenant", properties.db().url(), properties.db().app(), TENANT_POOL_SIZE);
	}

	@Bean
	@SystemDataSource
	HikariDataSource systemDataSource(AppProperties properties) {
		return pool("system", properties.db().url(), properties.db().system(), SYSTEM_POOL_SIZE);
	}

	@Bean
	@Primary
	PlatformTransactionManager tenantTransactionManager(DataSource dataSource) {
		return new TenantTransactionManager(dataSource);
	}

	@Bean
	@SystemDataSource
	PlatformTransactionManager systemTransactionManager(@SystemDataSource DataSource dataSource) {
		return new JdbcTransactionManager(dataSource);
	}

	@Bean
	@Primary
	JdbcClient tenantJdbcClient(DataSource dataSource) {
		return JdbcClient.create(dataSource);
	}

	@Bean
	@SystemDataSource
	JdbcClient systemJdbcClient(@SystemDataSource DataSource dataSource) {
		return JdbcClient.create(dataSource);
	}

	private static HikariDataSource pool(String name, String url, AppProperties.Credentials credentials, int size) {
		HikariConfig config = new HikariConfig();
		config.setPoolName(name);
		config.setJdbcUrl(url);
		config.setUsername(credentials.username());
		config.setPassword(credentials.password());
		config.setMaximumPoolSize(size);
		return new HikariDataSource(config);
	}

}
