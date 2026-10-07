package com.emailservice.templates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.emailservice.templates.engine.TemplateEngine;
import com.emailservice.templates.lint.HtmlLinter;
import com.emailservice.templates.schema.VariablesSchema;
import com.emailservice.templates.schema.VariablesValidator;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every template kept as code under templates/ must pass the checks the service runs on save,
 * publish and preview, so a broken template fails the build instead of the publish script.
 */
class TemplatesAsCodeTest {

	static final JsonMapper JSON = JsonMapper.builder().build();

	static Stream<Path> templates() throws IOException {
		try (Stream<Path> files = Files.walk(Path.of("templates"))) {
			return files.filter(path -> path.getFileName().toString().equals("template.json")).toList().stream();
		}
	}

	@ParameterizedTest
	@MethodSource("templates")
	void templatePassesTheServiceChecks(Path definition) throws IOException {
		Path dir = definition.getParent();
		JsonNode template = JSON.readTree(Files.readString(definition));
		assertThat(template.get("key").stringValue()).isEqualTo(dir.getFileName().toString());
		assertThat(template.get("category").stringValue()).isIn("SECURITY", "TRANSACTIONAL", "NOTICE");

		VariablesSchema schema = VariablesSchema.parse(template.get("variablesSchema"), "variablesSchema");
		JsonNode preview = template.get("previewVariables");
		assertThat(VariablesValidator.validate(schema, preview)).as("previewVariables").isEmpty();

		TemplateEngine engine = new TemplateEngine(JSON);
		List<String> locales = List.copyOf(template.get("subject").propertyNames());
		assertThat(locales).isNotEmpty().allSatisfy(locale -> assertThat(locale).isIn("es-CR", "en"));
		for (String locale : locales) {
			String html = Files.readString(dir.resolve(locale + ".html"));
			Path textFile = dir.resolve(locale + ".txt");
			String text = Files.exists(textFile) ? Files.readString(textFile) : null;
			var sources = new TemplateEngine.Sources(template.get("subject").get(locale).stringValue(), html, text);

			assertThatNoException().as(locale).isThrownBy(() -> engine.check(sources));
			assertThat(HtmlLinter.lint(html)).as(locale + " lint").isEmpty();
			assertThat(HtmlLinter.checkUrlVariables(html, schema)).as(locale + " URL variables").isEmpty();
			TemplateEngine.Rendered rendered = engine.render(sources, preview, locale, ZoneId.of("America/Costa_Rica"));
			assertThat(rendered.subject()).as(locale + " subject").isNotBlank();
			assertThat(rendered.html()).as(locale + " html").doesNotContain("{{");
		}
	}

}
