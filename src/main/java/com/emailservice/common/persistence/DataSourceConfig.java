package com.emailservice.common.persistence;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import com.emailservice.common.config.AppProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Two pools (ADR-0008): the primary one runs as email_app under RLS; the system one runs as
 * email_system and is only reachable through the {@link SystemDataSource} qualifier. The primary
 * transaction manager, which binds the tenant for RLS, lives in tenancy (TenancyConfig).
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

	/**
	 * 10 and 4 connections per instance in every deployed environment. The sizes are properties only
	 * so the test suite can run smaller pools; they are not part of the documented configuration.
	 */
	@Bean
	@Primary
	HikariDataSource tenantDataSource(AppProperties properties,
			@Value("${app.db.tenant-pool-size:10}") int tenantPoolSize) {
		return pool("tenant", properties.db().url(), properties.db().app(), tenantPoolSize);
	}

	@Bean
	@SystemDataSource
	HikariDataSource systemDataSource(AppProperties properties,
			@Value("${app.db.system-pool-size:4}") int systemPoolSize) {
		return pool("system", properties.db().url(), properties.db().system(), systemPoolSize);
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
