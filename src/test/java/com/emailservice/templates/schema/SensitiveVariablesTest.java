package com.emailservice.templates.schema;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** AC-23.5: x-sensitive variables are removed, at any depth, and nothing else. */
class SensitiveVariablesTest {

	static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void removesSensitiveValuesAtAnyDepthAndKeepsTheRest() {
		VariablesSchema schema = VariablesSchema.parse(JSON.readTree("""
				{"properties": {"firstName": {"type": "string"},
				 "resetUrl": {"type": "string", "format": "uri", "x-sensitive": true},
				 "order": {"type": "object", "properties": {"code": {"type": "string", "x-sensitive": true},
				                                            "total": {"type": "number"}}},
				 "codes": {"type": "array", "items": {"type": "string", "x-sensitive": true}},
				 "lines": {"type": "array", "items": {"type": "object", "properties": {
				   "sku": {"type": "string"}, "token": {"type": "string", "x-sensitive": true}}}}}}
				"""), "variablesSchema");
		JsonNode variables = JSON.readTree("""
				{"firstName": "Ana", "resetUrl": "https://x.test/r?t=1", "extra": "kept",
				 "order": {"code": "123456", "total": 10}, "codes": ["a", "b"],
				 "lines": [{"sku": "S1", "token": "t1"}, {"sku": "S2", "token": "t2"}]}
				""");

		JsonNode stripped = SensitiveVariables.strip(schema, variables);

		assertThat(stripped.toString()).isEqualTo("""
				{"firstName":"Ana","extra":"kept","order":{"total":10},"codes":[],"lines":[{"sku":"S1"},{"sku":"S2"}]}""");
		assertThat(variables.get("resetUrl")).as("input not modified").isNotNull();
	}

}
