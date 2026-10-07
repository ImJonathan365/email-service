package com.emailservice.tenancy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.emailservice.support.PostgresTestDatabase;
import com.emailservice.support.PostgresTestDatabase.Role;
import com.emailservice.support.TenantFixture;

/**
 * ADR-0008 review criterion: RLS must not add more than 20 % to tenant query latency. Runs the
 * same tenant transactions as email_app (set_config + RLS) and as email_system (BYPASSRLS).
 * Not part of the test suite: ./gradlew benchmarkRls
 */
@Tag("benchmark")
class RlsCostBenchmark {

	static final int TENANTS = 20;

	static final int MESSAGES_PER_TENANT = 5_000;

	static final int WARMUP = 1_000;

	static final int ITERATIONS = 5_000;

	static final String LIST = """
			SELECT id, status, to_email, created_at FROM email_message
			WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 20
			""";

	static final String BY_ID = "SELECT id, status, to_email FROM email_message WHERE tenant_id = ? AND id = ?";

	record Sample(UUID tenantId, UUID messageId) {
	}

	@Test
	void measure() throws Exception {
		PostgresTestDatabase.migrate();
		List<Sample> samples = seed();

		StringBuilder report = new StringBuilder("RLS cost (ADR-0008), PostgreSQL 18, ")
			.append(TENANTS * MESSAGES_PER_TENANT)
			.append(" messages in ")
			.append(TENANTS)
			.append(" tenants, ")
			.append(ITERATIONS)
			.append(" transactions per case; microseconds per transaction\n\n");
		report.append(String.format("%-36s %8s %8s %8s%n", "case", "p50", "p95", "p99"));
		for (String[] query : new String[][] { { "list latest 20 by tenant", LIST }, { "get by tenant + id", BY_ID } }) {
			long[] app = run(Role.APP, true, query[1], samples);
			long[] systemWithSetConfig = run(Role.SYSTEM, true, query[1], samples);
			long[] system = run(Role.SYSTEM, false, query[1], samples);
			report.append(query[0]).append('\n');
			report.append(row("  A email_app: set_config + RLS", app));
			report.append(row("  B email_system: set_config only", systemWithSetConfig));
			report.append(row("  C email_system: query only", system));
			report.append(overheadRow("  total A vs C", app, system));
			report.append(overheadRow("  policy evaluation A vs B", app, systemWithSetConfig));
			report.append('\n');
		}
		report.append("Plan as email_app (list):\n").append(plan(samples.getFirst()));

		Path output = Path.of("build/reports/rls-benchmark.txt");
		Files.createDirectories(output.getParent());
		Files.writeString(output, report);
		System.out.println(report);
	}

	private static List<Sample> seed() throws SQLException {
		List<Sample> samples = new ArrayList<>();
		try (Connection system = PostgresTestDatabase.connect(Role.SYSTEM)) {
			for (int t = 0; t < TENANTS; t++) {
				TenantFixture fixture = TenantFixture.create(system);
				try (PreparedStatement insert = system.prepareStatement("""
						INSERT INTO email_message (id, tenant_id, template_id, template_version_id, category, priority,
						    to_email, from_email, from_name, status, created_at)
						SELECT gen_random_uuid(), ?, ?, ?, 'TRANSACTIONAL', 1, 'user' || g || '@example.test',
						       'no-reply@example.test', 'Bench', 'QUEUED', now() - g * interval '1 minute'
						FROM generate_series(1, ?) g
						""")) {
					insert.setObject(1, fixture.tenantId());
					insert.setObject(2, fixture.templateId());
					insert.setObject(3, fixture.templateVersionId());
					insert.setInt(4, MESSAGES_PER_TENANT);
					insert.executeUpdate();
				}
				samples.add(new Sample(fixture.tenantId(), fixture.messageId()));
			}
			try (Statement statement = system.createStatement()) {
				statement.execute("ANALYZE email_message");
			}
		}
		return samples;
	}

	private static long[] run(Role role, boolean setTenant, String sql, List<Sample> samples) throws SQLException {
		Random random = new Random(42);
		long[] nanos = new long[ITERATIONS];
		try (Connection connection = PostgresTestDatabase.connect(role)) {
			connection.setAutoCommit(false);
			try (PreparedStatement context = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)");
					PreparedStatement query = connection.prepareStatement(sql)) {
				for (int i = 0; i < WARMUP + ITERATIONS; i++) {
					Sample sample = samples.get(random.nextInt(samples.size()));
					long start = System.nanoTime();
					if (setTenant) {
						context.setString(1, sample.tenantId().toString());
						context.execute();
					}
					query.setObject(1, sample.tenantId());
					if (BY_ID.equals(sql)) {
						query.setObject(2, sample.messageId());
					}
					try (ResultSet rs = query.executeQuery()) {
						while (rs.next()) {
							rs.getObject(1);
						}
					}
					connection.commit();
					if (i >= WARMUP) {
						nanos[i - WARMUP] = System.nanoTime() - start;
					}
				}
			}
		}
		Arrays.sort(nanos);
		return nanos;
	}

	private static String plan(Sample sample) throws SQLException {
		StringBuilder plan = new StringBuilder();
		try (Connection app = PostgresTestDatabase.connect(Role.APP)) {
			app.setAutoCommit(false);
			try (PreparedStatement context = app.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
				context.setString(1, sample.tenantId().toString());
				context.execute();
			}
			try (PreparedStatement explain = app.prepareStatement("EXPLAIN " + LIST)) {
				explain.setObject(1, sample.tenantId());
				try (ResultSet rs = explain.executeQuery()) {
					while (rs.next()) {
						plan.append("  ").append(rs.getString(1)).append('\n');
					}
				}
			}
			app.rollback();
		}
		return plan.toString();
	}

	private static long percentile(long[] sorted, int p) {
		return sorted[Math.min(sorted.length - 1, (int) Math.ceil(p / 100.0 * sorted.length) - 1)] / 1_000;
	}

	private static String row(String name, long[] sorted) {
		return String.format("%-36s %8d %8d %8d%n", name, percentile(sorted, 50), percentile(sorted, 95),
				percentile(sorted, 99));
	}

	private static String overheadRow(String name, long[] measured, long[] baseline) {
		return String.format("%-36s %7.1f%% %7.1f%% %7.1f%%%n", name, overhead(measured, baseline, 50),
				overhead(measured, baseline, 95), overhead(measured, baseline, 99));
	}

	private static double overhead(long[] app, long[] system, int p) {
		return 100.0 * (percentile(app, p) - percentile(system, p)) / percentile(system, p);
	}

}
