package com.emailservice.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Qualifier;

import com.emailservice.common.persistence.SystemDataSource;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.properties.HasAnnotations;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/** Module and DataSource boundaries from docs/05 §2 and ADR-0008. */
final class ArchitectureRules {

	/** Packages allowed to use the email_system role (ADR-0008), plus where its beans are defined. */
	static final String[] SYSTEM_DATASOURCE_PACKAGES = { "com.emailservice.sending.worker..",
			"com.emailservice.events..", "com.emailservice.tenancy.auth..", "com.emailservice.tenancy.admin..",
			"com.emailservice.abuse..", "com.emailservice.maintenance..", "com.emailservice.common.persistence.." };

	static final String[] PROVIDER_SDK_PACKAGES = { "com.resend..", "com.svix..", "com.postmarkapp..",
			"software.amazon.awssdk..", "jakarta.mail..", "javax.mail..", "org.eclipse.angus.mail..",
			"org.springframework.mail..", "org.simplejavamail.." };

	static final ArchRule SYSTEM_DATASOURCE_QUALIFIER_ONLY_IN_ALLOWED_PACKAGES = noClasses().that()
		.resideOutsideOfPackages(SYSTEM_DATASOURCE_PACKAGES)
		.should()
		.dependOnClassesThat()
		.areAssignableTo(SystemDataSource.class)
		.because("only the packages listed in ADR-0008 may use the email_system (BYPASSRLS) role")
		.allowEmptyShould(true);

	static final ArchRule SYSTEM_BEANS_NOT_INJECTED_BY_NAME_OUTSIDE_ALLOWED_PACKAGES = classes().that()
		.resideOutsideOfPackages(SYSTEM_DATASOURCE_PACKAGES)
		.should(notInjectSystemBeansByName())
		.because("a @Qualifier(\"system...\") bean name must not bypass the @SystemDataSource check")
		.allowEmptyShould(true);

	static final ArchRule PROVIDER_SDK_ONLY_IN_PROVIDER = noClasses().that()
		.resideOutsideOfPackage("com.emailservice.provider..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage(PROVIDER_SDK_PACKAGES)
		.because("provider SDKs stay behind EmailSender/WebhookVerifier (docs/05 §2)");

	static final ArchRule TEMPLATES_DO_NOT_KNOW_SENDING = noClasses().that()
		.resideInAPackage("com.emailservice.templates..")
		.should()
		.dependOnClassesThat()
		.resideInAPackage("com.emailservice.sending..")
		.because("templates receive text and variables and return rendered text (docs/05 §2)");

	private ArchitectureRules() {
	}

	private static ArchCondition<JavaClass> notInjectSystemBeansByName() {
		return new ArchCondition<>("not inject beans qualified by a name starting with 'system'") {
			@Override
			public void check(JavaClass javaClass, ConditionEvents events) {
				Stream<HasAnnotations<?>> injectionPoints = Stream.concat(
						javaClass.getFields().stream(),
						Stream.concat(javaClass.getConstructors().stream(), javaClass.getMethods().stream())
							.flatMap(code -> code.getParameters().stream()));
				injectionPoints.forEach(point -> point.getAnnotations().stream()
					.filter(annotation -> annotation.getRawType().isEquivalentTo(Qualifier.class))
					.map(ArchitectureRules::qualifierValue)
					.filter(name -> name.startsWith("system"))
					.forEach(name -> events.add(SimpleConditionEvent.violated(point,
							javaClass.getName() + " injects '" + name + "' by name"))));
			}
		};
	}

	private static String qualifierValue(JavaAnnotation<?> annotation) {
		return annotation.get("value").map(String::valueOf).orElse("");
	}

}
