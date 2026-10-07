package com.emailservice.templates.engine;

import java.io.IOException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.emailservice.common.api.ApiException;
import com.emailservice.common.api.ErrorCode;
import com.emailservice.common.api.Problem.FieldError;
import com.github.jknack.handlebars.Context;
import com.github.jknack.handlebars.EscapingStrategy;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.HandlebarsError;
import com.github.jknack.handlebars.HandlebarsException;
import com.github.jknack.handlebars.Template;
import com.github.jknack.handlebars.context.MapValueResolver;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The hardened Handlebars engine (ADR-0011). The single component that checks and renders
 * templates, used by preview and by sending alike (AC-06.3). HTML is escaped only in the HTML
 * body; subject and text are not, and the subject loses CR/LF (AC-04.9).
 */
@Component
public class TemplateEngine {

	private final Handlebars htmlEngine = harden(EscapingStrategy.HTML_ENTITY);

	private final Handlebars plainEngine = harden(EscapingStrategy.NOOP);

	private final JsonMapper jsonMapper;

	public TemplateEngine(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	public record Sources(String subject, String html, String text) {
	}

	public record Rendered(String subject, String html, String text) {
	}

	/** Thrown when variables break the render; the message never contains variable values. */
	public static final class RenderException extends RuntimeException {

		RenderException(String message) {
			super(message, null, false, false);
		}

	}

	/**
	 * Validates the three sources: unsafe constructs first (422 UNSAFE_TEMPLATE_CONSTRUCT), then
	 * syntax (422 TEMPLATE_SYNTAX_ERROR), with the line of each problem (AC-04.4, AC-04.5).
	 */
	public void check(Sources sources) {
		List<FieldError> unsafe = new ArrayList<>();
		List<FieldError> syntax = new ArrayList<>();
		scan("subjectTemplate", sources.subject(), unsafe, syntax);
		scan("htmlTemplate", sources.html(), unsafe, syntax);
		scan("textTemplate", sources.text(), unsafe, syntax);
		if (!unsafe.isEmpty()) {
			throw new ApiException(ErrorCode.UNSAFE_TEMPLATE_CONSTRUCT, "The template uses constructs that are not allowed.",
					unsafe);
		}
		compile("subjectTemplate", sources.subject(), syntax);
		compile("htmlTemplate", sources.html(), syntax);
		compile("textTemplate", sources.text(), syntax);
		if (!syntax.isEmpty()) {
			throw new ApiException(ErrorCode.TEMPLATE_SYNTAX_ERROR, "The template has syntax errors.", syntax);
		}
	}

	public Rendered render(Sources sources, JsonNode variables, String locale, ZoneId zone) {
		Map<String, Object> model = jsonMapper.convertValue(variables, new TypeReference<Map<String, Object>>() {
		});
		String subject = apply(plainEngine, sources.subject(), model, locale, zone).replaceAll("[\\r\\n]", "");
		String html = apply(htmlEngine, sources.html(), model, locale, zone);
		String text = sources.text() == null ? null : apply(plainEngine, sources.text(), model, locale, zone);
		return new Rendered(subject, html, text);
	}

	private String apply(Handlebars engine, String source, Map<String, Object> model, String locale, ZoneId zone) {
		// Stored templates were checked when saved; scanning again keeps raw output out even if one was not.
		if (TagScanner.scan(source).stream().anyMatch(TagScanner.Finding::unsafe)) {
			throw new RenderException("The template contains constructs that are not allowed.");
		}
		Context context = Context.newBuilder(model).resolver(MapValueResolver.INSTANCE).build();
		context.data(FormatHelpers.LOCALE, locale);
		context.data(FormatHelpers.ZONE, zone);
		try {
			Template template = engine.compileInline(source);
			return template.apply(context);
		}
		catch (HandlebarsException ex) {
			throw new RenderException(renderFailure(ex));
		}
		catch (IOException | RuntimeException ex) {
			throw new RenderException("The template could not be rendered with these variables.");
		}
		finally {
			context.destroy();
		}
	}

	private static void scan(String field, String source, List<FieldError> unsafe, List<FieldError> syntax) {
		if (source == null) {
			return;
		}
		for (TagScanner.Finding finding : TagScanner.scan(source)) {
			FieldError error = new FieldError(field, "line " + finding.line() + ": " + finding.message());
			(finding.unsafe() ? unsafe : syntax).add(error);
		}
	}

	private void compile(String field, String source, List<FieldError> syntax) {
		if (source == null || syntax.stream().anyMatch(error -> error.field().equals(field))) {
			return;
		}
		try {
			plainEngine.compileInline(source);
		}
		catch (HandlebarsException ex) {
			HandlebarsError error = ex.getError();
			syntax.add(new FieldError(field,
					error == null ? "invalid template" : "line " + error.line + ": " + error.reason));
		}
		catch (IOException ex) {
			syntax.add(new FieldError(field, "invalid template"));
		}
	}

	/** Keeps the helper's own message, which names the helper but never a value. */
	private static String renderFailure(HandlebarsException ex) {
		Throwable cause = ex.getCause();
		if (cause instanceof IllegalArgumentException && cause.getMessage() != null) {
			return cause.getMessage();
		}
		return "The template could not be rendered with these variables.";
	}

	private static Handlebars harden(EscapingStrategy escaping) {
		return new Handlebars(new NoTemplateLoader()).with(new WhitelistHelperRegistry())
			.with(escaping)
			.prettyPrint(false)
			.infiniteLoops(false);
	}

}
