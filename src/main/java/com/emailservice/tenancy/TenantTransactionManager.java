package com.emailservice.tenancy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * Binds every tenant transaction to {@link TenantContext} for RLS (ADR-0008). The setting is
 * transaction-local, so a pooled connection cannot carry it into another request.
 */
public class TenantTransactionManager extends JdbcTransactionManager {

	public TenantTransactionManager(DataSource dataSource) {
		super(dataSource);
	}

	@Override
	protected void prepareTransactionalConnection(Connection connection, TransactionDefinition definition)
			throws SQLException {
		super.prepareTransactionalConnection(connection, definition);
		// Refuse instead of running without context: a missing tenant is a bug, not an empty result.
		UUID tenantId = TenantContext.current()
			.orElseThrow(() -> new IllegalStateException("Tenant transaction started without a tenant context"));
		try (PreparedStatement statement = connection
			.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
			statement.setString(1, tenantId.toString());
			statement.execute();
		}
	}

}
