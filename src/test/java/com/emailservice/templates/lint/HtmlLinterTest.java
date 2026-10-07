package com.emailservice.templates.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.emailservice.common.api.Problem.FieldError;
import com.emailservice.templates.schema.VariablesSchema;

import tools.jackson.databind.json.JsonMapper;

/** AC-04.7 (HTML context linter) and AC-04.8 (URL variables need format uri). */
class HtmlLinterTest {

	static final VariablesSchema SCHEMA = VariablesSchema.parse(JsonMapper.builder().build().readTree("""
			{"properties": {"resetUrl": {"type": "string", "format": "uri"}, "firstName": {"type": "string"},
			 "user": {"type": "object", "properties": {"avatar": {"type": "string", "format": "uri"}}}}}
			"""), "variablesSchema");

	@ParameterizedTest
	@ValueSource(strings = { "<script>alert(1)</script>", "<iframe src=\"https://x.test\"></iframe>",
			"<object data=\"x\"></object>", "<embed src=\"x\">", "<form action=\"https://x.test\"><input></form>",
			"<style>p { color: {{color}} }</style>", "<p style=\"color: {{color}}\">x</p>",
			"<a href=\"#\" onclick=\"go('{{id}}')\">x</a>", "<img src=x onerror=\"{{x}}\">",
			"<a href={{resetUrl}}>x</a>", "<td width={{w}}>x</td>", "<a {{#if x}}href=\"y\"{{/if}}>x</a>",
			"<td onclick=\"{{x}}\">outside any table</td>", "<P STYLE=\"color:{{c}}\">x</P>", "<SCRIPT>x</SCRIPT>" })
	void ac_04_7_rejectsVariablesInDangerousContextsAndActiveTags(String html) {
		assertThat(HtmlLinter.lint(html)).isNotEmpty().allSatisfy(error -> assertThat(error.field()).isEqualTo("htmlTemplate"));
	}

	@Test
	void ac_04_7_acceptsVariablesInTextAndQuotedAttributes() {
		String html = """
				<html><body style="font-family:sans-serif">
				<style>p { margin: 0 }</style>
				<h1 title="{{firstName}}">Hola {{firstName}}</h1>
				{{#if vip}}<p class="vip">VIP</p>{{/if}}
				<a href="{{resetUrl}}">Restablecer</a> <img src='{{user.avatar}}' alt="">
				</body></html>
				""";
		assertThat(HtmlLinter.lint(html)).isEmpty();
	}

	@Test
	void ac_04_8_urlAttributesNeedUriVariables() {
		String html = """
				<a href="{{resetUrl}}">a</a> <img src="{{user.avatar}}"> <a href="{{@root.resetUrl}}">b</a>
				<a href="https://app.example.test/reset?t={{firstName}}">c</a> <img srcset="{{missing}} 2x">
				<td background="{{formatDate when}}"></td>
				""";
		List<FieldError> errors = HtmlLinter.checkUrlVariables(html, SCHEMA);
		assertThat(errors).extracting(FieldError::message)
			.containsExactlyInAnyOrder(
					"'firstName' is used in a URL attribute and must be declared with \"format\": \"uri\"",
					"'missing' is used in a URL attribute and must be declared with \"format\": \"uri\"",
					"'formatDate' is used in a URL attribute and must be declared with \"format\": \"uri\"");
	}

	@Test
	void textOutsideTagsMayContainEqualsSigns() {
		assertThat(HtmlLinter.lint("<p>Total = {{total}}</p>")).isEmpty();
	}

}
