package com.emailservice.templates.schema;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.emailservice.common.api.Problem.FieldError;
import com.emailservice.templates.schema.VariablesSchema.Property;

import tools.jackson.databind.JsonNode;

/**
 * AC-07.8: every variable declared with format uri must be https and point to one of the tenant's
 * allowedLinkHosts, exactly or as a subdomain. Keeps a leaked key from turning a legitimate
 * template into a phishing link. Used by preview and by sending.
 */
public final class UrlPolicy {

	private UrlPolicy() {
	}

	public static List<FieldError> check(VariablesSchema schema, JsonNode variables, List<String> allowedHosts) {
		List<FieldError> errors = new ArrayList<>();
		if (variables != null && variables.isObject()) {
			checkObject(schema.properties(), variables, "variables", allowedHosts, errors);
		}
		return errors;
	}

	static boolean allowed(String value, List<String> allowedHosts) {
		URI uri;
		try {
			uri = new URI(value);
		}
		catch (URISyntaxException ex) {
			return false;
		}
		if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null) {
			return false;
		}
		String host = uri.getHost().toLowerCase(Locale.ROOT);
		return allowedHosts.stream()
			.map(allowed -> allowed.toLowerCase(Locale.ROOT))
			.anyMatch(allowed -> host.equals(allowed) || host.endsWith("." + allowed));
	}

	private static void checkObject(Map<String, Property> properties, JsonNode object, String path,
			List<String> allowedHosts, List<FieldError> errors) {
		for (Map.Entry<String, Property> entry : properties.entrySet()) {
			JsonNode value = object.get(entry.getKey());
			if (value != null && !value.isNull()) {
				checkValue(entry.getValue(), value, path + "." + entry.getKey(), allowedHosts, errors);
			}
		}
	}

	private static void checkValue(Property property, JsonNode value, String path, List<String> allowedHosts,
			List<FieldError> errors) {
		switch (property.type()) {
			case STRING -> {
				if (property.format() == VariablesSchema.Format.URI && value.isString()
						&& !allowed(value.stringValue(), allowedHosts)) {
					errors.add(new FieldError(path, "must be an https URL on one of the tenant's allowedLinkHosts"));
				}
			}
			case ARRAY -> {
				if (value.isArray()) {
					int index = 0;
					for (JsonNode item : value.values()) {
						checkValue(property.items(), item, path + "[" + index++ + "]", allowedHosts, errors);
					}
				}
			}
			case OBJECT -> {
				if (value.isObject()) {
					checkObject(property.properties(), value, path, allowedHosts, errors);
				}
			}
			default -> {
			}
		}
	}

}
