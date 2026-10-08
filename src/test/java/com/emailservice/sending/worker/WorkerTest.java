package com.emailservice.sending.worker;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import com.emailservice.provider.EmailSender;
import com.emailservice.support.IntegrationTest;
import com.emailservice.support.PostgresTestDatabase;
import com.emailservice.support.PostgresTestDatabase.Role;

/**
 * Base for worker tests: a recording stub provider instead of any real one, and a quiet queue.
 * The claim spans all tenants, so messages left QUEUED by other test classes are cancelled first.
 */
@Import(WorkerTest.StubProvider.class)
abstract class WorkerTest extends IntegrationTest {

	@TestConfiguration
	static class StubProvider {

		@Bean
		@Primary
		RecordingEmailSender recordingEmailSender() {
			return new RecordingEmailSender();
		}

	}

	@Autowired
	RecordingEmailSender sender;

	@Autowired
	QueueWorker worker;

	@Autowired
	StuckMessageSweeper sweeper;

	@Autowired
	EmailSender activeSender;

	@BeforeEach
	void quietQueue() throws Exception {
		sender.reset();
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
