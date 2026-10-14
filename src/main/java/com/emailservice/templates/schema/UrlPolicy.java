package com.emailservice.templates.schema;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.emailservice.common.api.Problem.FieldError;
import com.emailservice.common.config.AppEnv;
import com.emailservice.templates.schema.VariablesSchema.Property;

import tools.jackson.databind.JsonNode;

/**
 * AC-07.8: every variable declared with format uri must be https and point to one of the tenant's
 * allowedLinkHosts, exactly or as a subdomain. Keeps a leaked key from turning a legitimate
 * template into a phishing link. Used by preview and by sending.
 * <p>
 * With APP_ENV=local only, http or https links to localhost and *.localhost are also accepted, so
 * a product running on the developer's machine can link to itself (ADR-0021). It derives from
 * APP_ENV alone, with no switch of its own, so no other environment can turn it on.
 */
public final class UrlPolicy {

	private UrlPolicy() {
	}

	public static List<FieldError> check(VariablesSchema schema, JsonNode variables, List<String> allowedHosts,
			AppEnv env) {
		List<FieldError> errors = new ArrayList<>();
		if (variables != null && variables.isObject()) {
			checkObject(schema.properties(), variables, "variables", new Rule(allowedHosts, env), errors);
		}
		return errors;
	}

	static boolean allowed(String value, List<String> allowedHosts, AppEnv env) {
		URI uri;
		try {
			uri = new URI(value);
		}
		catch (URISyntaxException ex) {
			return false;
		}
		if (uri.getScheme() == null || uri.getHost() == null || uri.getRawUserInfo() != null) {
			return false;
		}
		String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
		String host = uri.getHost().toLowerCase(Locale.ROOT);
		if (env == AppEnv.LOCAL && (scheme.equals("http") || scheme.equals("https"))
				&& (host.equals("localhost") || host.endsWith(".localhost"))) {
			return true;
		}
		if (!scheme.equals("https")) {
			return false;
		}
		return allowedHosts.stream()
			.map(allowed -> allowed.toLowerCase(Locale.ROOT))
			.anyMatch(allowed -> host.equals(allowed) || host.endsWith("." + allowed));
	}

	private record Rule(List<String> allowedHosts, AppEnv env) {
	}

	private static void checkObject(Map<String, Property> properties, JsonNode object, String path, Rule rule,
			List<FieldError> errors) {
		for (Map.Entry<String, Property> entry : properties.entrySet()) {
			JsonNode value = object.get(entry.getKey());
			if (value != null && !value.isNull()) {
				checkValue(entry.getValue(), value, path + "." + entry.getKey(), rule, errors);
			}
		}
	}

	private static void checkValue(Property property, JsonNode value, String path, Rule rule, List<FieldError> errors) {
		switch (property.type()) {
			case STRING -> {
				if (property.format() == VariablesSchema.Format.URI && value.isString()
						&& !allowed(value.stringValue(), rule.allowedHosts(), rule.env())) {
					errors.add(new FieldError(path, "must be an https URL on one of the tenant's allowedLinkHosts"));
				}
			}
			case ARRAY -> {
				if (value.isArray()) {
					int index = 0;
					for (JsonNode item : value.values()) {
						checkValue(property.items(), item, path + "[" + index++ + "]", rule, errors);
					}
				}
			}
			case OBJECT -> {
				if (value.isObject()) {
					checkObject(property.properties(), value, path, rule, errors);
				}
			}
			default -> {
			}
		}
	}

}
