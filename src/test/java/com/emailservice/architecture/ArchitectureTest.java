package com.emailservice.architecture;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.emailservice.architecture.fixture.InjectsSystemBeanByName;
import com.emailservice.architecture.fixture.InjectsSystemDataSource;
import com.emailservice.events.ArchFixtureAllowedSystemDataSourceUser;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/** NFR-12: ArchUnit guards for the rules in docs/05 §2 and ADR-0008. */
class ArchitectureTest {

	static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
		.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
		.importPackages("com.emailservice");

	@Test
	void systemDataSourceIsOnlyInjectedInAllowedPackages() {
		ArchitectureRules.SYSTEM_DATASOURCE_QUALIFIER_ONLY_IN_ALLOWED_PACKAGES.check(PRODUCTION_CLASSES);
		ArchitectureRules.SYSTEM_BEANS_NOT_INJECTED_BY_NAME_OUTSIDE_ALLOWED_PACKAGES.check(PRODUCTION_CLASSES);
	}

	@Test
	void providerSdkClassesAreOnlyUsedInsideProvider() {
		ArchitectureRules.PROVIDER_SDK_ONLY_IN_PROVIDER.check(PRODUCTION_CLASSES);
	}

	// The fixtures prove the rules detect violations instead of passing vacuously.

	@Test
	void qualifierRuleRejectsInjectionOutsideAllowedPackages() {
		JavaClasses classes = new ClassFileImporter().importClasses(InjectsSystemDataSource.class);
		assertThatThrownBy(
				() -> ArchitectureRules.SYSTEM_DATASOURCE_QUALIFIER_ONLY_IN_ALLOWED_PACKAGES.check(classes))
			.isInstanceOf(AssertionError.class)
			.hasMessageContaining(InjectsSystemDataSource.class.getName());
	}

	@Test
	void nameRuleRejectsInjectionByBeanNameOutsideAllowedPackages() {
		JavaClasses classes = new ClassFileImporter().importClasses(InjectsSystemBeanByName.class);
		assertThatThrownBy(
				() -> ArchitectureRules.SYSTEM_BEANS_NOT_INJECTED_BY_NAME_OUTSIDE_ALLOWED_PACKAGES.check(classes))
			.isInstanceOf(AssertionError.class)
			.hasMessageContaining("systemJdbcClient");
	}

	@Test
	void rulesAllowInjectionInsideAllowedPackages() {
		JavaClasses classes = new ClassFileImporter().importClasses(ArchFixtureAllowedSystemDataSourceUser.class);
		assertThatNoException().isThrownBy(() -> {
			ArchitectureRules.SYSTEM_DATASOURCE_QUALIFIER_ONLY_IN_ALLOWED_PACKAGES.check(classes);
			ArchitectureRules.SYSTEM_BEANS_NOT_INJECTED_BY_NAME_OUTSIDE_ALLOWED_PACKAGES.check(classes);
		});
	}

}
