package com.emailservice.templates.lint;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import com.emailservice.common.api.Problem.FieldError;
import com.emailservice.templates.schema.VariablesSchema;

/**
 * Context linter for htmlTemplate with a real HTML parser (ADR-0011). HTML escaping protects text
 * and quoted attribute values, but not script or style contexts, event handlers, unquoted
 * attributes or URLs, so variables are kept out of those and URL attributes need format uri.
 */
public final class HtmlLinter {

	static final String FIELD = "htmlTemplate";

	private static final Set<String> FORBIDDEN_TAGS = Set.of("script", "iframe", "object", "embed", "form");

	// Every attribute that makes a client load or navigate to a URL, not only href and src.
	private static final Set<String> URL_ATTRIBUTES = Set.of("href", "src", "srcset", "background", "poster", "cite",
			"action", "formaction", "longdesc", "usemap", "data", "xlink:href");

	private static final Pattern TAG = Pattern.compile("<[A-Za-z][^>]*>");

	private static final Pattern UNQUOTED_VARIABLE = Pattern.compile("=\\s*(?![\"'])[^\\s>]*\\{\\{");

	private static final Pattern VARIABLE = Pattern.compile("\\{\\{~?\\s*([^}\\s~]+)[^}]*}}");

	private HtmlLinter() {
	}

	/** AC-04.7: run when a version is saved. */
	public static List<FieldError> lint(String html) {
		List<FieldError> errors = new ArrayList<>();
		Matcher tags = TAG.matcher(html);
		while (tags.find()) {
			if (UNQUOTED_VARIABLE.matcher(tags.group()).find()) {
				errors.add(error("variables are not allowed in unquoted attributes: " + abbreviate(tags.group())));
			}
		}
		Document document = parse(html);
		for (Element element : document.getAllElements()) {
			String tag = element.normalName();
			if (FORBIDDEN_TAGS.contains(tag)) {
				errors.add(error("<" + tag + "> is not allowed"));
				continue;
			}
			if ("style".equals(tag) && element.wholeText().contains("{{")) {
				errors.add(error("variables are not allowed inside <style>"));
			}
			for (Attribute attribute : element.attributes()) {
				String name = attribute.getKey().toLowerCase(Locale.ROOT);
				String value = attribute.getValue();
				if (name.contains("{{") || name.contains("}}")) {
					errors.add(error("template tags are not allowed between attributes of <" + tag + ">"));
				}
				else if (value.contains("{{") && (name.startsWith("on") || "style".equals(name))) {
					errors.add(error("variables are not allowed in the " + name + " attribute of <" + tag + ">"));
				}
			}
		}
		return errors;
	}

	/** AC-04.8: run on publish, when the schema is mandatory. */
	public static List<FieldError> checkUrlVariables(String html, VariablesSchema schema) {
		List<FieldError> errors = new ArrayList<>();
		for (String path : urlVariables(html)) {
			String lookup = path.replaceFirst("^(@root\\.|this\\.)", "");
			boolean isUri = schema.property(lookup)
				.map(property -> property.format() == VariablesSchema.Format.URI)
				.orElse(false);
			if (!isUri) {
				errors.add(error("'" + path + "' is used in a URL attribute and must be declared with \"format\": \"uri\""));
			}
		}
		return errors;
	}

	static Set<String> urlVariables(String html) {
		Set<String> paths = new LinkedHashSet<>();
		for (Element element : parse(html).getAllElements()) {
			for (Attribute attribute : element.attributes()) {
				if (URL_ATTRIBUTES.contains(attribute.getKey().toLowerCase(Locale.ROOT))) {
					Matcher matcher = VARIABLE.matcher(attribute.getValue());
					while (matcher.find()) {
						paths.add(matcher.group(1));
					}
				}
			}
		}
		return paths;
	}

	/**
	 * The XML parser keeps every element exactly where it was written. The HTML5 parser would
	 * repair the tree (dropping, say, a <td> outside a table), but the email carries the original
	 * source, so the linter must see everything in it.
	 */
	private static Document parse(String html) {
		return Jsoup.parse(html, "", Parser.xmlParser());
	}

	private static FieldError error(String message) {
		return new FieldError(FIELD, message);
	}

	private static String abbreviate(String text) {
		return text.length() <= 80 ? text : text.substring(0, 77) + "...";
	}

}
