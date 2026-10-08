package com.emailservice.provider;

import java.util.Properties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.emailservice.common.config.AppProperties;
import com.emailservice.provider.smtp.SmtpEmailSender;

/**
 * Picks the EmailSender from MAIL_PROVIDER (AC-14.2). Each implementation's infrastructure is
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
	@ConditionalOnProperty(prefix = "app.mail", name = "provider", havingValue = "noop")
	EmailSender noopEmailSender() {
		return new NoopEmailSender();
	}

}
