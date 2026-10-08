package com.emailservice.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSender;

import com.emailservice.common.config.AppEnv;
import com.emailservice.common.config.AppProperties;
import com.emailservice.common.config.AppRole;
import com.emailservice.provider.smtp.SmtpEmailSender;

/** AC-14.2: MAIL_PROVIDER picks the sender, and only the chosen one's infrastructure exists. */
class ProviderConfigTest {

	static AppProperties properties(String provider) {
		var credentials = new AppProperties.Credentials("u", "p");
		return new AppProperties(AppEnv.LOCAL, AppRole.WORKER,
				new AppProperties.Db("jdbc:postgresql://localhost/db", credentials, credentials, credentials), null, null,
				null, new AppProperties.Mail(provider, "localhost", 1025, false, 3_000, 10_000),
				new AppProperties.Sending("hash-key-0123456789abcdef0123456789", "accept", List.of(), 262_144), null);
	}

	ApplicationContextRunner runner(String provider) {
		return new ApplicationContextRunner().withPropertyValues("app.mail.provider=" + provider)
			.withBean(AppProperties.class, () -> properties(provider))
			.withUserConfiguration(ProviderConfig.class);
	}

	@Test
	void noopHasNoMailInfrastructure() {
		runner("noop").run(context -> {
			assertThat(context).hasSingleBean(EmailSender.class);
			assertThat(context.getBean(EmailSender.class)).isInstanceOf(NoopEmailSender.class);
			assertThat(context).doesNotHaveBean(JavaMailSender.class);
		});
	}

	@Test
	void smtpUsesTheSmtpSenderWithoutConnectingAtStartup() {
		// Nothing listens on localhost:1025 here: creating the sender must not try to connect.
		runner("smtp").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(EmailSender.class)).isInstanceOf(SmtpEmailSender.class);
		});
	}

}
