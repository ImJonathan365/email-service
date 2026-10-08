package com.emailservice.sending;

import static com.emailservice.sending.SendingTestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.net.Socket;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.emailservice.support.IntegrationTest;
import com.emailservice.support.PostgresTestDatabase;
import com.emailservice.support.PostgresTestDatabase.Role;

/** FR-08: idempotency enforced by the database, per tenant, before anything else counts. */
class IdempotencyTest extends IntegrationTest {

	SendingTestSupport a;

	@BeforeEach
	void setUp() throws Exception {
		a = new SendingTestSupport(http(), "SECURITY");
	}

	static String key() {
		return "password-reset:" + UUID.randomUUID();
	}

	@Test
	void ac_08_1_sameKeyAndBodyReturnsTheOriginalWithoutASecondMessage() throws Exception {
		String key = key();
		HttpResponse<String> first = a.send(a.body(""), key);
		// Same request, different key order and whitespace: the canonical hash still matches.
		HttpResponse<String> repeat = a.send("{ \"variables\": " + SendingTestSupport.VARIABLES + ",\n \"to\": {\"name\": \"Ana\", "
				+ "\"email\": \"ana@example.test\"}, \"templateKey\": \"" + a.templateKey + "\" }", key);

		assertThat(first.statusCode()).isEqualTo(202);
		assertThat(repeat.statusCode()).isEqualTo(200);
		assertThat(json(repeat).get("id")).isEqualTo(json(first).get("id"));
		assertThat(json(repeat).get("createdAt")).isEqualTo(json(first).get("createdAt"));
		assertThat(SendingTestSupport.countMessages(a.tenant.id())).isEqualTo(1);
	}

	@Test
	void ac_08_2_sameKeyWithADifferentBodyIsAConflict() throws Exception {
		String key = key();
		a.send(a.body(""), key);
		HttpResponse<String> response = a.send(a.body("\"tags\": [\"other\"]"), key);
		assertThat(response.statusCode()).isEqualTo(409);
		assertThat(response.body()).contains("\"code\":\"IDEMPOTENCY_KEY_REUSED\"");
		assertThat(SendingTestSupport.countMessages(a.tenant.id())).isEqualTo(1);
	}

	@Test
	void ac_08_3_keysAreScopedPerTenant() throws Exception {
		SendingTestSupport b = new SendingTestSupport(http(), "SECURITY");
		String key = key();
		HttpResponse<String> fromA = a.send(a.body(""), key);
		HttpResponse<String> fromB = b.send(b.body(""), key);
		assertThat(fromA.statusCode()).isEqualTo(202);
		assertThat(fromB.statusCode()).isEqualTo(202);
		assertThat(json(fromA).get("id")).isNotEqualTo(json(fromB).get("id"));
	}

	@Test
	void ac_08_4_concurrentRequestsWithTheSameKeyCreateExactlyOneMessage() throws Exception {
		String key = key();
		int requests = 20;
		CountDownLatch start = new CountDownLatch(1);
		List<Future<HttpResponse<String>>> futures = new ArrayList<>();
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < requests; i++) {
				futures.add(executor.submit(() -> {
					start.await();
					return a.send(a.body(""), key);
				}));
			}
			start.countDown();
			List<HttpResponse<String>> responses = new ArrayList<>();
			for (Future<HttpResponse<String>> future : futures) {
				responses.add(future.get());
			}

			assertThat(responses).extracting(HttpResponse::statusCode).containsOnly(202, 200);
			assertThat(responses).filteredOn(r -> r.statusCode() == 202).hasSize(1);
			assertThat(responses).extracting(r -> json(r).get("id").stringValue()).containsOnly(
					json(responses.getFirst()).get("id").stringValue());
		}
		try (Connection system = PostgresTestDatabase.connect(Role.SYSTEM);
				PreparedStatement statement = system.prepareStatement(
						"SELECT count(*) FROM email_message WHERE tenant_id = ? AND idempotency_key = ?")) {
			statement.setObject(1, a.tenant.id());
			statement.setString(2, key);
			try (ResultSet rs = statement.executeQuery()) {
				rs.next();
				assertThat(rs.getLong(1)).isEqualTo(1);
			}
		}
	}

	@Test
	void ac_08_5_keyMustBePrintableAsciiUpTo256() throws Exception {
		HttpResponse<String> tooLong = a.send(a.body(""), "x".repeat(257));
		assertThat(tooLong.statusCode()).isEqualTo(422);
		assertThat(tooLong.body()).contains("\"field\":\"Idempotency-Key\"");
		// java.net.http turns non-ASCII header characters into '?', so send the UTF-8 bytes raw.
		assertThat(rawSendStatus("clave-con-\u00f1")).isEqualTo(422);
		assertThat(a.send(a.body(""), "k".repeat(256)).statusCode()).isEqualTo(202);
	}

	private int rawSendStatus(String idempotencyKey) throws Exception {
		byte[] body = a.body("").getBytes(StandardCharsets.UTF_8);
		String head = "POST /v1/emails HTTP/1.1\r\nHost: localhost\r\nAuthorization: " + a.product.bearer()
				+ "\r\nContent-Type: application/json\r\nContent-Length: " + body.length
				+ "\r\nConnection: close\r\nIdempotency-Key: " + idempotencyKey + "\r\n\r\n";
		try (Socket socket = new Socket("localhost", port())) {
			OutputStream out = socket.getOutputStream();
			out.write(head.getBytes(StandardCharsets.UTF_8));
			out.write(body);
			out.flush();
			String statusLine = new String(socket.getInputStream().readNBytes(12), StandardCharsets.US_ASCII);
			return Integer.parseInt(statusLine.substring(9, 12));
		}
	}

	@Test
	void ac_08_6_requestsWithoutAKeyAreAcceptedAndCounted() throws Exception {
		double before = counter("email_requests_without_idempotency_total");
		assertThat(a.send(a.body(""), null).statusCode()).isEqualTo(202);
		assertThat(a.send(a.body(""), null).statusCode()).isEqualTo(202);
		assertThat(counter("email_requests_without_idempotency_total")).isEqualTo(before + 2);
		assertThat(SendingTestSupport.countMessages(a.tenant.id())).isEqualTo(2);
	}

	@Test
	void ac_08_7_aRepeatIsAnsweredBeforeValidation() throws Exception {
		String key = key();
		HttpResponse<String> first = a.send(a.body(""), key);
		// The template gets a new published version afterwards; the repeat still returns the original.
		int next = a.createVersion(a.templateKey, "es-CR", "Nuevo {{firstName}}");
		a.operator(http().post("/v1/templates/" + a.templateKey + "/versions/" + next + "/publish")).send();
		HttpResponse<String> repeat = a.send(a.body(""), key);
		assertThat(repeat.statusCode()).isEqualTo(200);
		assertThat(json(repeat).get("templateVersion")).isEqualTo(json(first).get("templateVersion"));
	}

	private double counter(String name) throws Exception {
		return http().get("/actuator/prometheus")
			.send()
			.body()
			.lines()
			.filter(line -> line.startsWith(name))
			.mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)))
			.findFirst()
			.orElse(0);
	}

}
