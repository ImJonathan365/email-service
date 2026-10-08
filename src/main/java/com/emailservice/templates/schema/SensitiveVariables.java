package com.emailservice.templates.schema;

import java.util.Map;

import com.emailservice.templates.schema.VariablesSchema.Property;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Removes the variables marked x-sensitive (reset links, codes) once a message is SENT or
 * terminal (AC-23.5), including inside nested objects and array items. The rest is kept until
 * the retention purge.
 */
public final class SensitiveVariables {

	private SensitiveVariables() {
	}

	/** A copy of {@code variables} without sensitive values; the input is not modified. */
	public static JsonNode strip(VariablesSchema schema, JsonNode variables) {
		if (variables == null || !variables.isObject()) {
			return variables;
		}
		ObjectNode copy = (ObjectNode) variables.deepCopy();
		stripObject(schema.properties(), copy);
		return copy;
	}

	private static void stripObject(Map<String, Property> properties, ObjectNode object) {
		for (Map.Entry<String, Property> entry : properties.entrySet()) {
			Property property = entry.getValue();
			JsonNode value = object.get(entry.getKey());
			if (value == null) {
				continue;
			}
			if (property.sensitive()) {
				object.remove(entry.getKey());
			}
			else {
				stripValue(property, value);
			}
		}
	}

	private static void stripValue(Property property, JsonNode value) {
		if (property.type() == VariablesSchema.Type.OBJECT && value instanceof ObjectNode object) {
			stripObject(property.properties(), object);
		}
		else if (property.type() == VariablesSchema.Type.ARRAY && value instanceof ArrayNode array) {
			Property items = property.items();
			for (int i = array.size() - 1; i >= 0; i--) {
				if (items.sensitive()) {
					array.remove(i);
				}
				else {
					stripValue(items, array.get(i));
				}
			}
		}
	}

}
