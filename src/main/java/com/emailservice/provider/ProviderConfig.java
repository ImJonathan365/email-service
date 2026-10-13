package com.emailservice.provider;

import java.time.Clock;
import java.time.Duration;
import java.util.Properties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.emailservice.common.config.AppProperties;
import com.emailservice.provider.resend.ResendEmailSender;
import com.emailservice.provider.resend.SvixWebhookVerifier;
import com.emailservice.provider.smtp.SmtpEmailSender;

import tools.jackson.databind.json.JsonMapper;

/**
 * Picks the EmailSender from MAIL_PROVIDER (AC-14.2) and the webhook verifier from its secret. Each implementation's infrastructure is
 * created only when it is selected: with noop there is no JavaMailSender and nothing connects.
 */
@Configuration(proxyBeanMethods = false)
class ProviderConfig {

	@Bean
	@ConditionalOnProperty(prefix = "app.mail", name = "provider", havingValue = "smtp")
	EmailSender smtpEmailSender(AppProperties properties) {
		AppProperties.Mail mail = properties.mail();
		JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
		mailSender.setHost(mail.smtpHost());
		mailSender.setPort(mail.smtpPort());
		mailSender.setDefaultEncoding("UTF-8");
		Properties smtp = new Properties();
		smtp.setProperty("mail.smtp.connectiontimeout", String.valueOf(mail.connectTimeoutMs()));
		smtp.setProperty("mail.smtp.timeout", String.valueOf(mail.readTimeoutMs()));
		smtp.setProperty("mail.smtp.writetimeout", String.valueOf(mail.readTimeoutMs()));
		mailSender.setJavaMailProperties(smtp);
		return new SmtpEmailSender(mailSender);
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.mail", name = "provider", havingValue = "resend")
	EmailSender resendEmailSender(AppProperties properties, JsonMapper jsonMapper, Clock clock) {
		return new ResendEmailSender(properties.provider().resendApiUrl(), properties.provider().resendApiKey(),
				Duration.ofMillis(properties.mail().connectTimeoutMs()), Duration.ofMillis(properties.mail().readTimeoutMs()),
				jsonMapper, clock);
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.mail", name = "provider", havingValue = "noop")
	EmailSender noopEmailSender() {
		return new NoopEmailSender();
	}

	/** Without a secret (local SMTP) there is nothing to verify against, so /webhooks/resend is a 404. */
	@Bean
	@Conditional(WebhookSecretConfigured.class)
	WebhookVerifier resendWebhookVerifier(AppProperties properties, Clock clock) {
		return new SvixWebhookVerifier(properties.provider().webhookSigningSecrets(),
				Duration.ofSeconds(properties.provider().webhookToleranceSeconds()), clock);
	}

	static final class WebhookSecretConfigured implements Condition {

		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			String secrets = context.getEnvironment().getProperty("app.provider.webhook-signing-secrets", "");
			return !secrets.isBlank();
		}

	}

}
