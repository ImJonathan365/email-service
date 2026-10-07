package com.emailservice.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.emailservice.support.PostgresTestDatabase.Role;

/** Reads audit rows written for one request, to assert what FR-22 recorded. */
public final class AuditRows {

	private AuditRows() {
	}

	public record Row(String action, String actorType, String actorId, String tenantId, String ip,
			String metadata) {
	}

	public static List<Row> forRequest(String requestId) throws SQLException {
		try (Connection system = PostgresTestDatabase.connect(Role.SYSTEM);
				PreparedStatement statement = system.prepareStatement("""
						SELECT action, actor_type, actor_id, tenant_id::text, host(ip), metadata::text
						FROM audit_log WHERE request_id = ? ORDER BY created_at, action
						""")) {
			statement.setString(1, requestId);
			List<Row> rows = new ArrayList<>();
			try (ResultSet rs = statement.executeQuery()) {
				while (rs.next()) {
					rows.add(new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
							rs.getString(5), rs.getString(6)));
				}
			}
			return rows;
		}
	}

}
