plugins {
	java
	alias(libs.plugins.spring.boot)
	alias(libs.plugins.spring.dependency.management)
}

group = "com.emailservice"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation(libs.spring.boot.starter.actuator)
	implementation(libs.spring.boot.starter.data.jdbc)
	implementation(libs.spring.boot.starter.flyway)
	implementation(libs.spring.boot.starter.validation)
	implementation(libs.spring.boot.starter.webmvc)
	implementation(libs.flyway.database.postgresql)
	implementation(libs.springdoc.openapi.webmvc.ui)
	runtimeOnly(libs.micrometer.registry.prometheus)
	runtimeOnly(libs.postgresql)
	testImplementation(libs.spring.boot.starter.actuator.test)
	testImplementation(libs.spring.boot.starter.data.jdbc.test)
	testImplementation(libs.spring.boot.starter.flyway.test)
	testImplementation(libs.spring.boot.starter.validation.test)
	testImplementation(libs.spring.boot.starter.webmvc.test)
	testImplementation(libs.spring.boot.testcontainers)
	testImplementation(libs.testcontainers.junit.jupiter)
	testImplementation(libs.testcontainers.postgresql)
	testImplementation(libs.archunit.junit5)
	testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.bootJar {
	archiveFileName = "app.jar"
}

tasks.withType<Test> {
	useJUnitPlatform()
	inputs.file("scripts/init-db-roles.sql")
	inputs.files(fileTree("contracts"))
	systemProperty("contract.update", project.hasProperty("updateContract"))
}
