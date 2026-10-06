package com.emailservice.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.emailservice.support.IntegrationTest;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * AC-25.2: the committed contract must match the generated spec. To regenerate it after an
 * intended API change: ./gradlew test --tests '*OpenApiContractTest' -PupdateContract
 */
class OpenApiContractTest extends IntegrationTest {

	static final Path CONTRACT = Path.of("contracts/email-service.openapi.json");

	static final JsonMapper JSON = JsonMapper.builder()
		.enable(SerializationFeature.INDENT_OUTPUT)
		.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
		.build();

	@LocalServerPort
	int port;

	@Test
	void committedContractMatchesGeneratedSpec() throws Exception {
		HttpResponse<String> response = HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs")).build(),
					HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);
		String generated = canonical(response.body());

		if (Boolean.getBoolean("contract.update")) {
			Files.createDirectories(CONTRACT.getParent());
			Files.writeString(CONTRACT, generated);
		}

		assertThat(CONTRACT).as("contract file; regenerate with -PupdateContract").exists();
		assertThat(canonical(Files.readString(CONTRACT)))
			.as("contracts/email-service.openapi.json differs from the generated spec; "
					+ "regenerate it with -PupdateContract and review the diff")
			.isEqualTo(generated);
	}

	private static String canonical(String json) {
		Map<String, Object> tree = JSON.readValue(json, new TypeReference<>() {
		});
		return JSON.writeValueAsString(tree) + "\n";
	}

}
