package com.emailservice.templates.schema;

import java.util.Map;

import com.emailservice.templates.schema.VariablesSchema.Property;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * Removes the variables marked x-sensitive (reset links, codes) once a message is SENT or
 * terminal (AC-23.5), including inside nested objects and array items. The rest is kept until
 * the retention purge.
 */
public final class SensitiveVariables {

	private SensitiveVariables() {
	}

	/** What stored rendered content shows instead of a sensitive value (AC-19.3). */
	public static final String REDACTED = "[REDACTED]";

	/** A copy of {@code variables} without sensitive values; the input is not modified. */
	public static JsonNode strip(VariablesSchema schema, JsonNode variables) {
		return copy(schema, variables, null);
	}

	/**
	 * A copy with every sensitive value replaced by {@link #REDACTED}, for the stored copy of the
	 * rendered content: the template renders the same, without the secret.
	 */
	public static JsonNode redact(VariablesSchema schema, JsonNode variables) {
		return copy(schema, variables, StringNode.valueOf(REDACTED));
	}

	/** {@code replacement} null removes the sensitive values; otherwise it takes their place. */
	private static JsonNode copy(VariablesSchema schema, JsonNode variables, JsonNode replacement) {
		if (variables == null || !variables.isObject()) {
			return variables;
		}
		ObjectNode copy = (ObjectNode) variables.deepCopy();
		stripObject(schema.properties(), copy, replacement);
		return copy;
	}

	private static void stripObject(Map<String, Property> properties, ObjectNode object, JsonNode replacement) {
		for (Map.Entry<String, Property> entry : properties.entrySet()) {
			Property property = entry.getValue();
			JsonNode value = object.get(entry.getKey());
			if (value == null) {
				continue;
			}
			if (property.sensitive()) {
				if (replacement == null) {
					object.remove(entry.getKey());
				}
				else {
					object.set(entry.getKey(), replacement);
				}
			}
			else {
				stripValue(property, value, replacement);
			}
		}
	}

	private static void stripValue(Property property, JsonNode value, JsonNode replacement) {
		if (property.type() == VariablesSchema.Type.OBJECT && value instanceof ObjectNode object) {
			stripObject(property.properties(), object, replacement);
		}
		else if (property.type() == VariablesSchema.Type.ARRAY && value instanceof ArrayNode array) {
			Property items = property.items();
			for (int i = array.size() - 1; i >= 0; i--) {
				if (items.sensitive()) {
					if (replacement == null) {
						array.remove(i);
					}
					else {
						array.set(i, replacement);
					}
				}
				else {
					stripValue(items, array.get(i), replacement);
				}
			}
		}
	}

}
