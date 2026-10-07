package com.emailservice.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.emailservice.support.IntegrationTest;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * AC-25.2 / AC-25.3: each committed contract must match its generated group. To regenerate after
 * an intended API change: ./gradlew test --tests '*OpenApiContractTest' -PupdateContract
 */
class OpenApiContractTest extends IntegrationTest {

	static final JsonMapper JSON = JsonMapper.builder()
		.enable(SerializationFeature.INDENT_OUTPUT)
		.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
		.build();

	@ParameterizedTest
	@CsvSource({ "public, contracts/email-service.openapi.json", "admin, contracts/email-service-admin.openapi.json" })
	void committedContractMatchesGeneratedSpec(String group, Path contract) throws Exception {
		HttpResponse<String> response = http().get("/v3/api-docs/" + group).send();
		assertThat(response.statusCode()).isEqualTo(200);
		String generated = canonical(response.body());

		if (Boolean.getBoolean("contract.update")) {
			Files.createDirectories(contract.getParent());
			Files.writeString(contract, generated);
		}

		assertThat(contract).as("contract file; regenerate with -PupdateContract").exists();
		assertThat(canonical(Files.readString(contract)))
			.as(contract + " differs from the generated spec; regenerate it with -PupdateContract and review the diff")
			.isEqualTo(generated);
	}

	private static String canonical(String json) {
		Map<String, Object> tree = JSON.readValue(json, new TypeReference<>() {
		});
		return JSON.writeValueAsString(tree) + "\n";
	}

}
