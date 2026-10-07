package com.emailservice.templates.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Whitelist check of every {{...}} tag before Handlebars ever compiles it (ADR-0011): only
 * variable paths, the block helpers if/unless/each/with and the format helpers are allowed. Raw
 * output, partials, decorators, delimiter changes, inverse sections, sub-expressions and hash
 * arguments are rejected, so the engine's broader grammar is never reachable.
 */
final class TagScanner {

	static final Set<String> BLOCK_HELPERS = Set.of("if", "unless", "each", "with");

	static final Set<String> INLINE_HELPERS = Set.of("formatDate", "formatNumber", "formatMoney");

	private static final Pattern PATH = Pattern
		.compile("(@root\\.|(\\.\\./)*)?(this|@index|@key|@first|@last|[A-Za-z_][A-Za-z0-9_]*)(\\.[A-Za-z_][A-Za-z0-9_]*)*");

	private static final Pattern LITERAL = Pattern.compile("\"[^\"]*\"|'[^']*'|-?[0-9]+(\\.[0-9]+)?|true|false");

	/** A problem at a 1-based line: an unsafe construct (AC-04.5) or plain bad syntax (AC-04.4). */
	record Finding(int line, String message, boolean unsafe) {
	}

	private TagScanner() {
	}

	static List<Finding> scan(String source) {
		List<Finding> findings = new ArrayList<>();
		int from = 0;
		while (true) {
			int open = source.indexOf("{{", from);
			if (open < 0) {
				return findings;
			}
			int line = lineOf(source, open);
			if (open > 0 && source.charAt(open - 1) == '\\') {
				// An escaped \{{ is literal text for Handlebars.
				from = open + 2;
				continue;
			}
			boolean longComment = source.startsWith("{{!--", open) || source.startsWith("{{~!--", open);
			int close = source.indexOf(longComment ? "--}}" : "}}", open + 2);
			if (close < 0) {
				findings.add(new Finding(line, "unclosed {{ tag", false));
				return findings;
			}
			String inner = source.substring(open + 2, close);
			from = close + (longComment ? 4 : 2);
			checkTag(inner, line, findings);
		}
	}

	private static void checkTag(String raw, int line, List<Finding> findings) {
		String tag = raw;
		if (tag.startsWith("~")) {
			tag = tag.substring(1);
		}
		if (tag.endsWith("~")) {
			tag = tag.substring(0, tag.length() - 1);
		}
		if (tag.startsWith("!")) {
			return;
		}
		if (tag.startsWith("{") || tag.startsWith("&")) {
			findings.add(new Finding(line, "unescaped output ({{{ }}} or {{& }}) is not allowed", true));
			return;
		}
		if (tag.startsWith(">") || tag.startsWith("#>")) {
			findings.add(new Finding(line, "partials are not allowed", true));
			return;
		}
		if (tag.startsWith("*") || tag.startsWith("#*")) {
			findings.add(new Finding(line, "decorators are not allowed", true));
			return;
		}
		if (tag.startsWith("=")) {
			findings.add(new Finding(line, "changing delimiters is not allowed", true));
			return;
		}
		if (tag.startsWith("^")) {
			findings.add(new Finding(line, "inverse sections are not allowed; use {{else}} or {{#unless}}", true));
			return;
		}
		String body = tag.strip();
		if (body.startsWith("#")) {
			List<String> tokens = tokens(body.substring(1), line, findings);
			if (tokens == null) {
				return;
			}
			if (tokens.isEmpty() || !BLOCK_HELPERS.contains(tokens.getFirst())) {
				findings.add(new Finding(line,
						"block '" + (tokens.isEmpty() ? "" : tokens.getFirst()) + "' is not allowed; use if, unless, each or with",
						true));
				return;
			}
			if (tokens.size() != 2) {
				findings.add(new Finding(line, "{{#" + tokens.getFirst() + "}} takes exactly one argument", true));
				return;
			}
			checkArguments(tokens.subList(1, tokens.size()), line, findings);
			return;
		}
		if (body.startsWith("/")) {
			String name = body.substring(1).strip();
			if (!BLOCK_HELPERS.contains(name)) {
				findings.add(new Finding(line, "closing tag '" + name + "' does not match an allowed block", true));
			}
			return;
		}
		List<String> tokens = tokens(body, line, findings);
		if (tokens == null) {
			return;
		}
		if (tokens.isEmpty()) {
			findings.add(new Finding(line, "empty tag", false));
			return;
		}
		String name = tokens.getFirst();
		if (name.equals("else")) {
			if (tokens.size() > 1 && !(tokens.size() == 3 && tokens.get(1).equals("if"))) {
				findings.add(new Finding(line, "only {{else}} and {{else if value}} are allowed", true));
			}
			else if (tokens.size() == 3) {
				checkArguments(tokens.subList(2, 3), line, findings);
			}
			return;
		}
		if (INLINE_HELPERS.contains(name)) {
			if (tokens.size() < 2 || tokens.size() > 3) {
				findings.add(new Finding(line, name + " takes a value and at most one option", true));
				return;
			}
			checkArguments(tokens.subList(1, tokens.size()), line, findings);
			return;
		}
		if (tokens.size() > 1) {
			findings.add(new Finding(line, "helper '" + name + "' is not allowed", true));
			return;
		}
		if (!PATH.matcher(name).matches()) {
			findings.add(new Finding(line, "'" + name + "' is not a variable name", true));
		}
	}

	private static void checkArguments(List<String> arguments, int line, List<Finding> findings) {
		for (String argument : arguments) {
			if (!PATH.matcher(argument).matches() && !LITERAL.matcher(argument).matches()) {
				findings.add(new Finding(line, "argument '" + argument + "' must be a variable or a literal", true));
			}
		}
	}

	/** Splits on whitespace outside quotes; null (with a finding) on syntax outside the subset. */
	private static List<String> tokens(String body, int line, List<Finding> findings) {
		List<String> tokens = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		char quote = 0;
		for (char c : body.toCharArray()) {
			if (quote != 0) {
				current.append(c);
				if (c == quote) {
					quote = 0;
				}
			}
			else if (c == '"' || c == '\'') {
				quote = c;
				current.append(c);
			}
			else if (Character.isWhitespace(c)) {
				if (!current.isEmpty()) {
					tokens.add(current.toString());
					current.setLength(0);
				}
			}
			else if (c == '(' || c == ')' || c == '=' || c == '[' || c == ']' || c == '|') {
				findings.add(new Finding(line, "sub-expressions, hash arguments and block parameters are not allowed", true));
				return null;
			}
			else {
				current.append(c);
			}
		}
		if (quote != 0) {
			findings.add(new Finding(line, "unterminated string literal", false));
			return null;
		}
		if (!current.isEmpty()) {
			tokens.add(current.toString());
		}
		return tokens;
	}

	private static int lineOf(String source, int index) {
		int line = 1;
		for (int i = 0; i < index; i++) {
			if (source.charAt(i) == '\n') {
				line++;
			}
		}
		return line;
	}

}
