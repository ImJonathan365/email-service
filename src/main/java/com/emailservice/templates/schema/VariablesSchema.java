package com.emailservice.templates.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.emailservice.common.api.Problem.FieldError;

import tools.jackson.databind.JsonNode;

/**
 * The closed JSON Schema subset of docs/06 §3.3 that describes a template's variables. Any keyword
 * outside the subset is rejected when the schema is saved, so what a template accepts is always
 * fully specified.
 */
public record VariablesSchema(Set<String> required, Map<String, Property> properties) {

	static final int MAX_DEPTH = 3;

	static final int MAX_STRING_LENGTH = 2_000;

	static final int DEFAULT_STRING_LENGTH = 500;

	static final int MAX_ARRAY_ITEMS = 500;

	static final int DEFAULT_ARRAY_ITEMS = 100;

	private static final Set<String> ROOT_KEYWORDS = Set.of("required", "properties");

	private static final Set<String> PROPERTY_KEYWORDS = Set.of("type", "format", "maxLength", "maxItems", "items",
			"properties", "x-sensitive");

	public enum Type {

		STRING, INTEGER, NUMBER, BOOLEAN, ARRAY, OBJECT

	}

	public enum Format {

		URI("uri"), EMAIL("email"), DATE_TIME("date-time");

		final String keyword;

		Format(String keyword) {
			this.keyword = keyword;
		}

	}

	/** One declared variable; {@code items} only for arrays and {@code properties} only for objects. */
	public record Property(Type type, Format format, int maxLength, int maxItems, Property items,
			Map<String, Property> properties, boolean sensitive) {
	}

	/** Thrown with every problem found, addressed by JSON path under {@code field}. */
	public static final class InvalidSchemaException extends RuntimeException {

		private final List<FieldError> errors;

		InvalidSchemaException(List<FieldError> errors) {
			super("Invalid variablesSchema", null, false, false);
			this.errors = List.copyOf(errors);
		}

		public List<FieldError> errors() {
			return errors;
		}

	}

	public static VariablesSchema parse(JsonNode node, String field) {
		List<FieldError> errors = new ArrayList<>();
		VariablesSchema schema = parseRoot(node, field, errors);
		if (!errors.isEmpty()) {
			throw new InvalidSchemaException(errors);
		}
		return schema;
	}

	/** Variables a message must provide; all locales of a template must agree on them (AC-37.6). */
	public Set<String> requiredVariables() {
		return required;
	}

	/** Looks up a dotted path such as {@code order.trackingUrl}, as used inside a template. */
	public Optional<Property> property(String dottedPath) {
		Map<String, Property> level = properties;
		Property found = null;
		for (String segment : dottedPath.split("\\.")) {
			if (level == null || !level.containsKey(segment)) {
				return Optional.empty();
			}
			found = level.get(segment);
			level = found.properties();
		}
		return Optional.ofNullable(found);
	}

	private static VariablesSchema parseRoot(JsonNode node, String field, List<FieldError> errors) {
		if (node == null || !node.isObject()) {
			errors.add(new FieldError(field, "must be an object with required and properties"));
			return null;
		}
		rejectUnknown(node, ROOT_KEYWORDS, field, errors);
		Map<String, Property> properties = parseProperties(node.get("properties"), field + ".properties", 1, errors);
		Set<String> required = new LinkedHashSet<>();
		JsonNode requiredNode = node.get("required");
		if (requiredNode != null) {
			if (!requiredNode.isArray()) {
				errors.add(new FieldError(field + ".required", "must be an array of property names"));
			}
			else {
				for (JsonNode name : requiredNode.values()) {
					if (!name.isString() || !properties.containsKey(name.stringValue())) {
						errors.add(new FieldError(field + ".required", "names an undeclared property: " + name));
					}
					else {
						required.add(name.stringValue());
					}
				}
			}
		}
		return new VariablesSchema(Set.copyOf(required), properties);
	}

	private static Map<String, Property> parseProperties(JsonNode node, String field, int depth,
			List<FieldError> errors) {
		Map<String, Property> properties = new LinkedHashMap<>();
		if (node == null) {
			return properties;
		}
		if (!node.isObject()) {
			errors.add(new FieldError(field, "must be an object"));
			return properties;
		}
		if (depth > MAX_DEPTH) {
			errors.add(new FieldError(field, "nesting deeper than " + MAX_DEPTH + " levels is not allowed"));
			return properties;
		}
		for (Map.Entry<String, JsonNode> entry : node.properties()) {
			String name = entry.getKey();
			if (!name.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) {
				errors.add(new FieldError(field + "." + name, "property names must be identifiers"));
				continue;
			}
			Property property = parseProperty(entry.getValue(), field + "." + name, depth, errors);
			if (property != null) {
				properties.put(name, property);
			}
		}
		return Map.copyOf(properties);
	}

	private static Property parseProperty(JsonNode node, String field, int depth, List<FieldError> errors) {
		if (node == null || !node.isObject()) {
			errors.add(new FieldError(field, "must be an object"));
			return null;
		}
		rejectUnknown(node, PROPERTY_KEYWORDS, field, errors);
		Type type = enumValue(node.get("type"), Type.class, field + ".type", errors);
		if (type == null) {
			return null;
		}
		Format format = null;
		if (node.has("format")) {
			format = parseFormat(node.get("format"), field + ".format", errors);
			onlyFor(type == Type.STRING, "format", "string", field, errors);
		}
		int maxLength = DEFAULT_STRING_LENGTH;
		if (node.has("maxLength")) {
			maxLength = bounded(node.get("maxLength"), MAX_STRING_LENGTH, field + ".maxLength", errors);
			onlyFor(type == Type.STRING, "maxLength", "string", field, errors);
		}
		int maxItems = DEFAULT_ARRAY_ITEMS;
		if (node.has("maxItems")) {
			maxItems = bounded(node.get("maxItems"), MAX_ARRAY_ITEMS, field + ".maxItems", errors);
			onlyFor(type == Type.ARRAY, "maxItems", "array", field, errors);
		}
		Property items = null;
		if (type == Type.ARRAY) {
			if (!node.has("items")) {
				errors.add(new FieldError(field + ".items", "is required for arrays"));
			}
			else {
				// Array items describe each element, not a new named level: depth counts name nesting.
				items = parseProperty(node.get("items"), field + ".items", depth, errors);
			}
		}
		else if (node.has("items")) {
			onlyFor(false, "items", "array", field, errors);
		}
		Map<String, Property> nested = null;
		if (type == Type.OBJECT) {
			if (!node.has("properties")) {
				errors.add(new FieldError(field + ".properties", "is required for objects"));
			}
			else {
				nested = parseProperties(node.get("properties"), field + ".properties", depth + 1, errors);
			}
		}
		else if (node.has("properties")) {
			onlyFor(false, "properties", "object", field, errors);
		}
		boolean sensitive = false;
		if (node.has("x-sensitive")) {
			if (!node.get("x-sensitive").isBoolean()) {
				errors.add(new FieldError(field + ".x-sensitive", "must be a boolean"));
			}
			else {
				sensitive = node.get("x-sensitive").booleanValue();
			}
		}
		return new Property(type, format, maxLength, maxItems, items, nested, sensitive);
	}

	private static void rejectUnknown(JsonNode node, Set<String> allowed, String field, List<FieldError> errors) {
		for (String keyword : node.propertyNames()) {
			if (!allowed.contains(keyword)) {
				errors.add(new FieldError(field + "." + keyword, "keyword is not supported"));
			}
		}
	}

	private static void onlyFor(boolean applies, String keyword, String type, String field, List<FieldError> errors) {
		if (!applies) {
			errors.add(new FieldError(field + "." + keyword, "only applies to type " + type));
		}
	}

	private static Format parseFormat(JsonNode node, String field, List<FieldError> errors) {
		for (Format format : Format.values()) {
			if (node.isString() && format.keyword.equals(node.stringValue())) {
				return format;
			}
		}
		errors.add(new FieldError(field, "must be one of uri, email, date-time"));
		return null;
	}

	private static <E extends Enum<E>> E enumValue(JsonNode node, Class<E> type, String field, List<FieldError> errors) {
		if (node != null && node.isString()) {
			for (E constant : type.getEnumConstants()) {
				if (constant.name().toLowerCase(Locale.ROOT).equals(node.stringValue())) {
					return constant;
				}
			}
		}
		errors.add(new FieldError(field, "must be one of string, integer, number, boolean, array, object"));
		return null;
	}

	private static int bounded(JsonNode node, int max, String field, List<FieldError> errors) {
		if (node == null || !node.isIntegralNumber() || node.intValue() < 1 || node.intValue() > max) {
			errors.add(new FieldError(field, "must be an integer between 1 and " + max));
			return max;
		}
		return node.intValue();
	}

}
