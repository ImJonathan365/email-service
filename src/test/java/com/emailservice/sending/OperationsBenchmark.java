package com.emailservice.sending;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.emailservice.support.IntegrationTest;
import com.emailservice.support.PostgresTestDatabase;
import com.emailservice.support.PostgresTestDatabase.Role;

/**
 * AC-20.2 with the reference dataset (owner, 2026-10-12): 4 M messages, 2.8 M in one tenant and
 * 400 k in each of the other three. Measured through the API (authentication, RLS and JSON
 * included) for the large tenant. Run with ./gradlew benchmarkOperations; the report goes to
 * build/reports/operations-benchmark.txt.
 */
@Tag("benchmark")
@Tag("operations")
class OperationsBenchmark extends IntegrationTest {

	static final int[] ROWS = { 2_800_000, 400_000, 400_000, 400_000 };

	static final int WARMUP = 30;

	static final int ITERATIONS = 200;

	static final long P95_LIMIT_MS = 500;

	record Tenant(SendingTestSupport support, List<String> templateKeys) {
	}

	@Test
	void ac_20_2_listingP95WithTheReferenceDataset() throws Exception {
		List<Tenant> tenants = new ArrayList<>();
		long seedStart = System.nanoTime();
		for (int rows : ROWS) {
			tenants.add(seed(rows));
		}
		analyze();
		long seedSeconds = (System.nanoTime() - seedStart) / 1_000_000_000;

		Tenant big = tenants.getFirst();
		Instant now = Instant.now();
		String day = "createdAfter=" + now.minus(31, ChronoUnit.DAYS) + "&createdBefore=" + now.minus(30, ChronoUnit.DAYS);
		Map<String, String> cases = new LinkedHashMap<>();
		cases.put("latest 20", "");
		cases.put("status=FAILED", "status=FAILED");
		cases.put("status=COMPLAINED (rare)", "status=COMPLAINED");
		cases.put("templateKey", "templateKey=" + big.templateKeys().get(2));
		cases.put("to (one address)", "to=user4242@example.test");
		cases.put("to (no messages)", "to=nobody@example.test");
		cases.put("tag=invoice (common)", "tag=invoice");
		cases.put("tag=rare", "tag=rare");
		cases.put("tag that does not exist", "tag=missing");
		cases.put("one day, 30 days ago", day);
		cases.put("status=FAILED + templateKey", "status=FAILED&templateKey=" + big.templateKeys().get(1));
		cases.put("tag=invoice + status=BOUNCED", "tag=invoice&status=BOUNCED");
		cases.put("limit=100", "limit=100");
		cases.put("page 50 (cursor)", "cursor=" + deepCursor(big, 50));

		StringBuilder report = new StringBuilder("GET /v1/emails (AC-20.2), PostgreSQL 18, ")
			.append(Arrays.stream(ROWS).sum())
			.append(" messages: ")
			.append(Arrays.toString(ROWS))
			.append(", seeded in ")
			.append(seedSeconds)
			.append(" s. Large tenant, ")
			.append(ITERATIONS)
			.append(" requests per case through the API; milliseconds.\n\n")
			.append(String.format("%-34s %8s %8s %8s%n", "case", "p50", "p95", "p99"));
		Map<String, Long> p95 = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : cases.entrySet()) {
			long[] millis = measure(big, "/v1/emails?" + entry.getValue());
			report.append(String.format("%-34s %8.1f %8.1f %8.1f%n", entry.getKey(), percentile(millis, 50) / 1e3,
					percentile(millis, 95) / 1e3, percentile(millis, 99) / 1e3));
			p95.put(entry.getKey(), Math.round(percentile(millis, 95) / 1000));
		}
		report.append("\nPlans as email_app with RLS:\n");
		report.append(plan(big, "tag=rare", new EmailViews.Filter(null, null, null, "rare", null, null)));
		report.append(plan(big, "tag=missing", new EmailViews.Filter(null, null, null, "missing", null, null)));
		report.append(plan(big, "status=COMPLAINED", new EmailViews.Filter("COMPLAINED", null, null, null, null, null)));
		report.append(plan(big, "to", new EmailViews.Filter(null, null, "user4242@example.test", null, null, null)));

		Path output = Path.of("build/reports/operations-benchmark.txt");
		Files.createDirectories(output.getParent());
		Files.writeString(output, report);
		System.out.println(report);
		assertThat(p95).allSatisfy((name, value) -> assertThat(value).as(name).isLessThanOrEqualTo(P95_LIMIT_MS));
	}

	/**
	 * A tenant with five published templates and {@code rows} messages spread over 65 days: mostly
	 * DELIVERED, some SENT, BOUNCED, FAILED and a few COMPLAINED; 200 k distinct recipients in the
	 * large tenant; a third of them tagged invoice, a third password-reset and one in 10 000 rare.
	 */
	Tenant seed(int rows) throws Exception {
		SendingTestSupport support = new SendingTestSupport(http(), "TRANSACTIONAL");
		List<String> keys = new ArrayList<>(List.of(support.templateKey));
		for (int i = 0; i < 4; i++) {
			keys.add(support.publishTemplate("TRANSACTIONAL", "<p>Hola {{firstName}}</p>",
					"{\"required\": [\"firstName\"], \"properties\": {\"firstName\": {\"type\": \"string\"}}}"));
		}
		try (Connection system = PostgresTestDatabase.connect(Role.SYSTEM);
				PreparedStatement insert = system.prepareStatement("""
						WITH versions AS (
						    SELECT row_number() OVER (ORDER BY t.key) - 1 AS n, t.id AS template_id, v.id AS version_id
						    FROM template t JOIN template_version v ON v.template_id = t.id AND v.status = 'PUBLISHED'
						    WHERE t.tenant_id = ? AND v.locale = 'es-CR'
						)
						INSERT INTO email_message (id, tenant_id, template_id, template_version_id, category, priority,
						    to_email, from_email, from_name, status, provider, provider_message_id, failure_code, tags,
						    created_at)
						SELECT gen_random_uuid(), ?, v.template_id, v.version_id, 'TRANSACTIONAL', 1,
						       'user' || (g % 200000) || '@example.test', 'no-reply@sender.test', 'Bench', s.status,
						       CASE WHEN s.status <> 'FAILED' THEN 'resend' END,
						       CASE WHEN s.status <> 'FAILED' THEN 'bench-' || gen_random_uuid() END,
						       CASE WHEN s.status = 'FAILED' THEN 'PROVIDER_REJECTED' END,
						       CASE WHEN g % 10000 = 0 THEN ARRAY['rare']
						            WHEN g % 3 = 0 THEN ARRAY['invoice']
						            WHEN g % 3 = 1 THEN ARRAY['password-reset'] ELSE ARRAY[]::text[] END,
						       now() - g * interval '2 seconds'
						FROM generate_series(1, ?) g
						JOIN versions v ON v.n = g % 5
						CROSS JOIN LATERAL (SELECT CASE WHEN g % 1000 < 850 THEN 'DELIVERED'
						                                WHEN g % 1000 < 930 THEN 'SENT'
						                                WHEN g % 1000 < 960 THEN 'BOUNCED'
						                                WHEN g % 1000 < 961 THEN 'COMPLAINED'
						                                ELSE 'FAILED' END AS status) s
						""")) {
			insert.setObject(1, support.tenant.id());
			insert.setObject(2, support.tenant.id());
			insert.setInt(3, rows);
			insert.executeUpdate();
		}
		return new Tenant(support, keys);
	}

	static void analyze() throws Exception {
		try (Connection owner = PostgresTestDatabase.connect(Role.OWNER); Statement statement = owner.createStatement()) {
			statement.execute("VACUUM ANALYZE email_message");
		}
	}

	long[] measure(Tenant tenant, String path) throws Exception {
		long[] nanos = new long[ITERATIONS];
		for (int i = -WARMUP; i < ITERATIONS; i++) {
			long start = System.nanoTime();
			HttpResponse<String> response = http().get(path)
				.header("Authorization", tenant.support().product.bearer())
				.send();
			long elapsed = System.nanoTime() - start;
			assertThat(response.statusCode()).as(path + ": " + response.body()).isEqualTo(200);
			if (i >= 0) {
				nanos[i] = elapsed / 1000;
			}
		}
		Arrays.sort(nanos);
		return nanos;
	}

	String deepCursor(Tenant tenant, int pages) throws Exception {
		String cursor = null;
		for (int i = 0; i < pages; i++) {
			HttpResponse<String> response = http()
				.get("/v1/emails" + (cursor == null ? "" : "?cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8)))
				.header("Authorization", tenant.support().product.bearer())
				.send();
			cursor = SendingTestSupport.json(response).get("nextCursor").stringValue();
		}
		return cursor;
	}

	static String plan(Tenant tenant, String name, EmailViews.Filter filter) throws Exception {
		EmailQueries.Listing listing = EmailQueries.listing(tenant.support().tenant.id(), filter, null, 21);
		try (Connection app = PostgresTestDatabase.connect(Role.APP)) {
			app.setAutoCommit(false);
			try (PreparedStatement set = app.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
				set.setString(1, tenant.support().tenant.id().toString());
				set.execute();
			}
			NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(new SingleConnectionDataSource(app, true));
			List<String> lines = jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) " + listing.sql(), listing.params(),
					String.class);
			app.rollback();
			return "-- " + name + "\n" + String.join("\n", lines) + "\n\n";
		}
	}

	static double percentile(long[] sorted, int p) {
		return sorted[Math.min(sorted.length - 1, (int) Math.ceil(p / 100.0 * sorted.length) - 1)];
	}

}
