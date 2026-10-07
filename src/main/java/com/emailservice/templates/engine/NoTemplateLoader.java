package com.emailservice.templates.engine;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import com.github.jknack.handlebars.io.TemplateLoader;
import com.github.jknack.handlebars.io.TemplateSource;

/** A loader that loads nothing: no partial can ever be resolved, from disk or classpath (ADR-0011). */
final class NoTemplateLoader implements TemplateLoader {

	@Override
	public TemplateSource sourceAt(String location) throws IOException {
		throw new FileNotFoundException("Template loading is disabled");
	}

	@Override
	public String resolve(String location) {
		return location;
	}

	@Override
	public String getPrefix() {
		return "";
	}

	@Override
	public String getSuffix() {
		return "";
	}

	@Override
	public void setPrefix(String prefix) {
	}

	@Override
	public void setSuffix(String suffix) {
	}

	@Override
	public void setCharset(Charset charset) {
	}

	@Override
	public Charset getCharset() {
		return StandardCharsets.UTF_8;
	}

}
