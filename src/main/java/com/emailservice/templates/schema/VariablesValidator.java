package com.emailservice.templates.schema;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.emailservice.common.api.Problem.FieldError;
import com.emailservice.templates.schema.VariablesSchema.Property;

import tools.jackson.databind.JsonNode;

/**
 * Checks a message's variables against its template's schema (AC-06.2). Undeclared variables are
 * allowed and ignored, as in JSON Schema.
 */
public final class VariablesValidator {

	// Deliberately simple: full address validation belongs to sending (FR-09).
	private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

	private VariablesValidator() {
	}

	public static List<FieldError> validate(VariablesSchema schema, JsonNode variables) {
		List<FieldError> errors = new ArrayList<>();
		if (variables == null || !variables.isObject()) {
			errors.add(new FieldError("variables", "must be an object"));
			return errors;
		}
		for (String name : schema.required()) {
			JsonNode value = variables.get(name);
			if (value == null || value.isNull()) {
				errors.add(new FieldError("variables." + name, "is required"));
			}
		}
		validateObject(schema.properties(), variables, "variables", errors);
		return errors;
	}

	private static void validateObject(Map<String, Property> properties, JsonNode object, String path,
			List<FieldError> errors) {
		for (Map.Entry<String, Property> entry : properties.entrySet()) {
			JsonNode value = object.get(entry.getKey());
			if (value != null && !value.isNull()) {
				validateValue(entry.getValue(), value, path + "." + entry.getKey(), errors);
			}
		}
	}

	private static void validateValue(Property property, JsonNode value, String path, List<FieldError> errors) {
		switch (property.type()) {
			case STRING -> {
				if (!value.isString()) {
					errors.add(new FieldError(path, "must be a string"));
					return;
				}
				String text = value.stringValue();
				if (text.length() > property.maxLength()) {
					errors.add(new FieldError(path, "must be at most " + property.maxLength() + " characters"));
				}
				if (property.format() != null && !matchesFormat(property.format(), text)) {
					errors.add(new FieldError(path, "must be a valid " + property.format().keyword));
				}
			}
			case INTEGER -> {
				if (!value.isIntegralNumber()) {
					errors.add(new FieldError(path, "must be an integer"));
				}
			}
			case NUMBER -> {
				if (!value.isNumber()) {
					errors.add(new FieldError(path, "must be a number"));
				}
			}
			case BOOLEAN -> {
				if (!value.isBoolean()) {
					errors.add(new FieldError(path, "must be a boolean"));
				}
			}
			case ARRAY -> {
				if (!value.isArray()) {
					errors.add(new FieldError(path, "must be an array"));
					return;
				}
				if (value.size() > property.maxItems()) {
					errors.add(new FieldError(path, "must have at most " + property.maxItems() + " items"));
					return;
				}
				int index = 0;
				for (JsonNode item : value.values()) {
					validateValue(property.items(), item, path + "[" + index++ + "]", errors);
				}
			}
			case OBJECT -> {
				if (!value.isObject()) {
					errors.add(new FieldError(path, "must be an object"));
					return;
				}
				validateObject(property.properties(), value, path, errors);
			}
		}
	}

	private static boolean matchesFormat(VariablesSchema.Format format, String text) {
		return switch (format) {
			case URI -> {
				try {
					URI uri = new URI(text);
					yield uri.isAbsolute() && uri.getHost() != null;
				}
				catch (URISyntaxException ex) {
					yield false;
				}
			}
			case EMAIL -> EMAIL.matcher(text).matches();
			case DATE_TIME -> {
				try {
					OffsetDateTime.parse(text);
					yield true;
				}
				catch (DateTimeParseException ex) {
					yield false;
				}
			}
		};
	}

}
