package com.emailservice.sending.worker;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

import com.emailservice.provider.EmailSender;
import com.emailservice.support.IntegrationTest;
import com.emailservice.support.MutableClock;
import com.emailservice.support.PostgresTestDatabase;
import com.emailservice.support.PostgresTestDatabase.Role;

/**
 * Base for worker tests: the shared recording provider instead of any real one, a closed circuit
 * breaker and a quiet queue. The claim spans all tenants, so messages left QUEUED by other test
 * classes are cancelled first.
 */
abstract class WorkerTest extends IntegrationTest {

	@Autowired
	RecordingEmailSender sender;

	@Autowired
	QueueWorker worker;

	@Autowired
	StuckMessageSweeper sweeper;

	@Autowired
	EmailSender activeSender;

	@Autowired
	CircuitBreaker breaker;

	@Autowired
	MutableClock clock;

	@BeforeEach
	void quietQueue() throws Exception {
		sender.reset();
		clock.reset();
		breaker.onSuccess();
		cancelPendingMessages();
	}

	static void cancelPendingMessages() throws Exception {
		sql("""
				UPDATE email_message SET status = 'CANCELED', finalized_at = now(), lock_token = NULL,
				    lock_expires_at = NULL, locked_by = NULL
				WHERE status IN ('QUEUED', 'SENDING')
				""");
	}

	static void sql(String statement, Object... params) throws Exception {
		try (Connection system = PostgresTestDatabase.connect(Role.SYSTEM);
				PreparedStatement prepared = system.prepareStatement(statement)) {
			for (int i = 0; i < params.length; i++) {
				prepared.setObject(i + 1, params[i]);
			}
			prepared.executeUpdate();
		}
	}

	static UUID id(String uuid) {
		return UUID.fromString(uuid);
	}

}
