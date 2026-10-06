package com.emailservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.core.env.StandardEnvironment;

import com.emailservice.common.config.AppRole;

@SpringBootApplication
@ConfigurationPropertiesScan
public class EmailServiceApplication {

	public static void main(String[] args) {
		AppRole role = AppRole.parse(new StandardEnvironment().getProperty("APP_ROLE", "all"));
		SpringApplication application = create(role);
		if (role == AppRole.MIGRATE) {
			// Flyway runs during context refresh; then the job exits with the context's exit code.
			System.exit(SpringApplication.exit(application.run(args)));
		}
		application.run(args);
	}

	static SpringApplication create(AppRole role) {
		SpringApplication application = new SpringApplication(EmailServiceApplication.class);
		if (role == AppRole.MIGRATE) {
			application.setWebApplicationType(WebApplicationType.NONE);
			application.setAdditionalProfiles(AppRole.MIGRATE_PROFILE);
		}
		return application;
	}

}
