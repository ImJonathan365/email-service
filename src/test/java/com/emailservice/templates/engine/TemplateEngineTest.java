package com.emailservice.templates.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.emailservice.common.api.ApiException;
import com.emailservice.common.api.ErrorCode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** ADR-0011 hardening and FR-04 / FR-37 rendering rules of the template engine. */
class TemplateEngineTest {

	static final JsonMapper JSON = JsonMapper.builder().build();

	static final ZoneId COSTA_RICA = ZoneId.of("America/Costa_Rica");

	final TemplateEngine engine = new TemplateEngine(JSON);

	static JsonNode json(String text) {
		return JSON.readTree(text);
	}

	static TemplateEngine.Sources html(String html) {
		return new TemplateEngine.Sources("Subject", html, null);
	}

	String renderHtml(String template, String variables, String locale) {
		return engine.render(html(template), json(variables), locale, COSTA_RICA).html();
	}

	@ParameterizedTest
	@ValueSource(strings = { "{{{name}}}", "{{& name}}", "{{~{name}~}}", "{{{{raw}}}}x{{{{/raw}}}}", "{{> footer}}",
			"{{#> layout}}x{{/layout}}", "{{*inline \"x\"}}", "{{#*inline \"x\"}}y{{/inline}}", "{{=<% %>=}}",
			"{{^items}}none{{/items}}", "{{lookup this \"x\"}}", "{{log name}}", "{{#custom name}}x{{/custom}}",
			"{{#items}}x{{/items}}", "{{#each (items)}}x{{/each}}", "{{name key=value}}", "{{#each items as |i|}}x{{/each}}",
			"{{embedded \"x\"}}", "{{precompile \"x\"}}", "{{formatDate}}", "{{[odd name]}}" })
	void ac_04_5_rejectsEveryConstructOutsideTheWhitelist(String template) {
		assertThatThrownBy(() -> engine.check(html(template))).isInstanceOfSatisfying(ApiException.class, ex -> {
			assertThat(ex.code()).isEqualTo(ErrorCode.UNSAFE_TEMPLATE_CONSTRUCT);
			assertThat(ex.errors()).first().satisfies(error -> {
				assertThat(error.field()).isEqualTo("htmlTemplate");
				assertThat(error.message()).startsWith("line 1: ");
			});
		});
	}

	@Test
	void acceptsTheWhitelist() {
		String template = """
				{{! a comment }}{{!-- another {{ comment }} --}}
				<p>Hola {{firstName}} {{this.lastName}} \\{{literal}}</p>
				{{#if vip}}VIP{{else if member}}member{{else}}guest{{/if}}
				{{#unless hidden}}shown{{/unless}}
				{{#each items}}{{@index}}: {{name}} {{@root.firstName}} {{../firstName}}{{/each}}
				{{#with order}}{{id}}{{/with}}
				{{formatDate when "long"}} {{formatNumber total 2}} {{formatMoney total "CRC"}}
				""";
		assertThatNoException().isThrownBy(() -> engine.check(new TemplateEngine.Sources("Hi {{firstName}}",
				template, "Hola {{firstName}}")));
	}

	@Test
	void ac_04_4_syntaxErrorsReportTheLine() {
		assertThatThrownBy(() -> engine.check(html("<p>\n{{#if vip}}\nunclosed</p>")))
			.isInstanceOfSatisfying(ApiException.class, ex -> {
				assertThat(ex.code()).isEqualTo(ErrorCode.TEMPLATE_SYNTAX_ERROR);
				assertThat(ex.errors().getFirst().message()).startsWith("line ");
			});
		assertThatThrownBy(() -> engine.check(new TemplateEngine.Sources("Hello {{name", "<p></p>", null)))
			.isInstanceOfSatisfying(ApiException.class, ex -> {
				assertThat(ex.code()).isEqualTo(ErrorCode.TEMPLATE_SYNTAX_ERROR);
				assertThat(ex.errors().getFirst().field()).isEqualTo("subjectTemplate");
			});
	}

	@Test
	void unsafeConstructsAreReportedBeforeSyntaxErrors() {
		assertThatThrownBy(() -> engine.check(new TemplateEngine.Sources("{{#if x}}", "{{{raw}}}", null)))
			.isInstanceOfSatisfying(ApiException.class,
					ex -> assertThat(ex.code()).isEqualTo(ErrorCode.UNSAFE_TEMPLATE_CONSTRUCT));
	}

	@Test
	void ac_04_9_escapesHtmlOnlyInTheHtmlBody() {
		TemplateEngine.Rendered rendered = engine.render(
				new TemplateEngine.Sources("Hola {{name}}", "<p>{{name}} {{markup}}</p>", "Hola {{name}}"),
				json("{\"name\": \"Ana & Juan\", \"markup\": \"<script>x</script>\"}"), "es-CR", COSTA_RICA);

		assertThat(rendered.html()).isEqualTo("<p>Ana &amp; Juan &lt;script&gt;x&lt;/script&gt;</p>");
		assertThat(rendered.subject()).isEqualTo("Hola Ana & Juan");
		assertThat(rendered.text()).isEqualTo("Hola Ana & Juan");
	}

	@Test
	void ac_04_9_subjectLosesLineBreaks() {
		TemplateEngine.Rendered rendered = engine.render(new TemplateEngine.Sources("Hi {{name}}", "<p></p>", null),
				json("{\"name\": \"Ana\\r\\nBcc: victim@example.test\"}"), "es-CR", COSTA_RICA);
		assertThat(rendered.subject()).isEqualTo("Hi AnaBcc: victim@example.test").doesNotContain("\r", "\n");
		assertThat(rendered.text()).isNull();
	}

	@Test
	void ac_37_5_formatsWithTheMessageLocaleAndTenantZone() {
		String template = "{{formatMoney amount \"CRC\"}}|{{formatMoney amount \"USD\"}}|{{formatNumber ratio 2}}|"
				+ "{{formatDate when \"long\"}}|{{formatDate day \"short\"}}";
		String variables = "{\"amount\": 1500, \"ratio\": 1234.5, \"when\": \"2026-10-12T08:00:00Z\", \"day\": \"2026-10-12\"}";

		// Frozen JDK 25 output: es-CR uses no-break spaces as grouping separators.
		assertThat(renderHtml(template, variables, "es-CR"))
			.isEqualTo("\u20A11\u00A0500,00|USD1\u00A0500,00|1\u00A0234,50|"
					+ "12 de octubre de 2026, 2:00\u202Fa.\u00A0m.|12/10/26");
		assertThat(renderHtml(template, variables, "en"))
			.isEqualTo("CRC1,500.00|$1,500.00|1,234.50|October 12, 2026, 2:00\u202FAM|10/12/26");
	}

	@Test
	void formatHelpersRejectBadInputWithoutEchoingIt() {
		assertThatThrownBy(() -> renderHtml("{{formatMoney amount \"EUR\"}}", "{\"amount\": 1}", "en"))
			.isInstanceOf(TemplateEngine.RenderException.class)
			.hasMessage("formatMoney needs the currency CRC or USD");
		assertThatThrownBy(() -> renderHtml("{{formatNumber amount}}", "{\"amount\": \"secret-value\"}", "en"))
			.isInstanceOf(TemplateEngine.RenderException.class)
			.message()
			.doesNotContain("secret-value");
		assertThatThrownBy(() -> renderHtml("{{formatDate when}}", "{\"when\": \"yesterday\"}", "en"))
			.isInstanceOf(TemplateEngine.RenderException.class);
	}

	@Test
	void renderRefusesUnsafeSourcesEvenIfTheyWereStored() {
		assertThatThrownBy(() -> renderHtml("{{{raw}}}", "{\"raw\": \"<b>x</b>\"}", "en"))
			.isInstanceOf(TemplateEngine.RenderException.class);
	}

	@Test
	void helperWhitelistCannotBeExtended() {
		assertThatThrownBy(() -> new WhitelistHelperRegistry().registerHelper("lookup", (context, options) -> ""))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThat(new WhitelistHelperRegistry().helpers()).extracting(Map.Entry::getKey)
			.containsExactlyInAnyOrder("if", "unless", "each", "with", "formatDate", "formatNumber", "formatMoney");
	}

	@Test
	void loopsAndConditionalsRender() {
		assertThat(renderHtml("{{#each items}}{{@index}}={{name}};{{/each}}{{#if vip}}V{{else}}G{{/if}}",
				"{\"items\": [{\"name\": \"a\"}, {\"name\": \"b\"}], \"vip\": false}", "en"))
			.isEqualTo("0=a;1=b;G");
	}

}
