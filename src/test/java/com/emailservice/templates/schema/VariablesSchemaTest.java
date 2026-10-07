package com.emailservice.templates.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.emailservice.common.api.Problem.FieldError;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** docs/06 §3.3: the closed variablesSchema subset (AC-05.5) and variable validation (AC-06.2). */
class VariablesSchemaTest {

	static final JsonMapper JSON = JsonMapper.builder().build();

	static JsonNode json(String text) {
		return JSON.readTree(text);
	}

	static VariablesSchema parse(String schema) {
		return VariablesSchema.parse(json(schema), "variablesSchema");
	}

	static List<String> fieldsOf(String schema) {
		try {
			parse(schema);
			return List.of();
		}
		catch (VariablesSchema.InvalidSchemaException ex) {
			return ex.errors().stream().map(FieldError::field).toList();
		}
	}

	static final String PASSWORD_RESET = """
			{"required": ["firstName", "resetUrl", "expiresInMinutes"],
			 "properties": {
			   "firstName": {"type": "string", "maxLength": 80},
			   "resetUrl": {"type": "string", "format": "uri", "x-sensitive": true},
			   "expiresInMinutes": {"type": "integer"},
			   "order": {"type": "object", "properties": {
			     "items": {"type": "array", "maxItems": 3, "items": {"type": "object", "properties": {
			       "name": {"type": "string"}}}}}}}}
			""";

	@Test
	void parsesTheDocumentedSubset() {
		VariablesSchema schema = parse(PASSWORD_RESET);
		assertThat(schema.requiredVariables()).containsExactlyInAnyOrder("firstName", "resetUrl", "expiresInMinutes");
		assertThat(schema.property("resetUrl")).hasValueSatisfying(p -> {
			assertThat(p.format()).isEqualTo(VariablesSchema.Format.URI);
			assertThat(p.sensitive()).isTrue();
		});
		assertThat(schema.property("firstName").orElseThrow().maxLength()).isEqualTo(80);
		assertThat(schema.property("order.items").orElseThrow().maxItems()).isEqualTo(3);
		assertThat(schema.property("order.missing")).isEmpty();
		assertThat(parse("{}").requiredVariables()).isEmpty();
	}

	@Test
	void appliesDocumentedDefaults() {
		VariablesSchema schema = parse("""
				{"properties": {"s": {"type": "string"}, "a": {"type": "array", "items": {"type": "string"}}}}
				""");
		assertThat(schema.property("s").orElseThrow().maxLength()).isEqualTo(500);
		assertThat(schema.property("a").orElseThrow().maxItems()).isEqualTo(100);
	}

	@Test
	void rejectsKeywordsOutsideTheSubset() {
		assertThat(fieldsOf("""
				{"required": [], "properties": {"a": {"type": "string", "pattern": "x", "minLength": 1}},
				 "additionalProperties": false}
				""")).containsExactlyInAnyOrder("variablesSchema.properties.a.pattern",
						"variablesSchema.properties.a.minLength", "variablesSchema.additionalProperties");
	}

	@Test
	void rejectsInvalidValues() {
		assertThat(fieldsOf("""
				{"required": ["ghost"], "properties": {
				  "a": {"type": "date"},
				  "b": {"type": "string", "format": "ipv4", "maxLength": 2001},
				  "c": {"type": "integer", "format": "uri", "maxItems": 5},
				  "d": {"type": "array"},
				  "e": {"type": "object"},
				  "f": {"type": "string", "x-sensitive": "yes"},
				  "bad-name": {"type": "string"}}}
				""")).containsExactlyInAnyOrder("variablesSchema.required", "variablesSchema.properties.a.type",
						"variablesSchema.properties.b.format", "variablesSchema.properties.b.maxLength",
						"variablesSchema.properties.c.format", "variablesSchema.properties.c.maxItems",
						"variablesSchema.properties.d.items", "variablesSchema.properties.e.properties",
						"variablesSchema.properties.f.x-sensitive", "variablesSchema.properties.bad-name");
	}

	@Test
	void limitsNestingToThreeLevels() {
		assertThat(fieldsOf("""
				{"properties": {"a": {"type": "object", "properties": {"b": {"type": "object", "properties": {
				  "c": {"type": "string"}}}}}}}
				""")).isEmpty();
		assertThat(fieldsOf("""
				{"properties": {"a": {"type": "object", "properties": {"b": {"type": "object", "properties": {
				  "c": {"type": "object", "properties": {"d": {"type": "string"}}}}}}}}}
				""")).containsExactly("variablesSchema.properties.a.properties.b.properties.c.properties");
	}

	@Test
	void ac_06_2_reportsMissingAndMistypedVariables() {
		VariablesSchema schema = parse(PASSWORD_RESET);
		List<FieldError> errors = VariablesValidator.validate(schema, json("""
				{"firstName": 42, "resetUrl": "not a uri",
				 "order": {"items": [{"name": "a"}, {"name": 1}, {"name": "c"}, {"name": "d"}]}}
				"""));
		assertThat(errors).extracting(FieldError::field)
			.containsExactlyInAnyOrder("variables.expiresInMinutes", "variables.firstName", "variables.resetUrl",
					"variables.order.items");
	}

	@Test
	void acceptsValidVariablesAndIgnoresUndeclaredOnes() {
		VariablesSchema schema = parse(PASSWORD_RESET);
		assertThat(VariablesValidator.validate(schema, json("""
				{"firstName": "Ana", "resetUrl": "https://app.example.test/reset?t=1", "expiresInMinutes": 30,
				 "extra": "ignored", "order": {"items": [{"name": "a"}]}}
				"""))).isEmpty();
	}

	@Test
	void checksLengthsFormatsAndTypes() {
		VariablesSchema schema = parse("""
				{"properties": {"s": {"type": "string", "maxLength": 3}, "e": {"type": "string", "format": "email"},
				 "d": {"type": "string", "format": "date-time"}, "n": {"type": "number"}, "b": {"type": "boolean"}}}
				""");
		assertThat(VariablesValidator.validate(schema, json("""
				{"s": "abcd", "e": "no-at-sign", "d": "2026-10-06", "n": "1", "b": "true"}
				"""))).extracting(FieldError::field)
			.containsExactlyInAnyOrder("variables.s", "variables.e", "variables.d", "variables.n", "variables.b");
		assertThat(VariablesValidator.validate(schema, json("""
				{"s": "abc", "e": "a@example.test", "d": "2026-10-06T10:00:00-06:00", "n": 1.5, "b": true}
				"""))).isEmpty();
	}

	@Test
	void nonObjectSchemaOrVariablesAreRejected() {
		assertThatThrownBy(() -> parse("[]")).isInstanceOf(VariablesSchema.InvalidSchemaException.class);
		assertThat(VariablesValidator.validate(parse("{}"), json("[]"))).extracting(FieldError::field)
			.containsExactly("variables");
	}

}
