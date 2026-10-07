package com.emailservice.templates.engine;

import java.io.File;
import java.io.InputStream;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.Set;

import com.github.jknack.handlebars.Decorator;
import com.github.jknack.handlebars.Helper;
import com.github.jknack.handlebars.HelperRegistry;
import com.github.jknack.handlebars.helper.EachHelper;
import com.github.jknack.handlebars.helper.IfHelper;
import com.github.jknack.handlebars.helper.UnlessHelper;
import com.github.jknack.handlebars.helper.WithHelper;

/**
 * Replaces Handlebars' default registry, whose constructor installs partial, embedded, lookup,
 * log, precompile and other helpers. This one holds exactly the ADR-0011 whitelist and refuses any
 * later registration, including JavaScript helpers and decorators.
 */
final class WhitelistHelperRegistry implements HelperRegistry {

	private final Map<String, Helper<?>> helpers = Map.of("if", IfHelper.INSTANCE, "unless", UnlessHelper.INSTANCE,
			"each", EachHelper.INSTANCE, "with", WithHelper.INSTANCE, "formatDate", FormatHelpers.FORMAT_DATE,
			"formatNumber", FormatHelpers.FORMAT_NUMBER, "formatMoney", FormatHelpers.FORMAT_MONEY);

	@Override
	@SuppressWarnings("unchecked")
	public <C> Helper<C> helper(String name) {
		return (Helper<C>) helpers.get(name);
	}

	@Override
	public Set<Map.Entry<String, Helper<?>>> helpers() {
		return helpers.entrySet();
	}

	@Override
	public Decorator decorator(String name) {
		return null;
	}

	@Override
	public <H> HelperRegistry registerHelper(String name, Helper<H> helper) {
		throw closed();
	}

	@Override
	public <H> HelperRegistry registerHelperMissing(Helper<H> helper) {
		throw closed();
	}

	@Override
	public HelperRegistry registerHelpers(Object helperSource) {
		throw closed();
	}

	@Override
	public HelperRegistry registerHelpers(Class<?> helperSource) {
		throw closed();
	}

	@Override
	public HelperRegistry registerHelpers(URI location) {
		throw closed();
	}

	@Override
	public HelperRegistry registerHelpers(File input) {
		throw closed();
	}

	@Override
	public HelperRegistry registerHelpers(String filename, Reader source) {
		throw closed();
	}

	@Override
	public HelperRegistry registerHelpers(String filename, InputStream source) {
		throw closed();
	}

	@Override
	public HelperRegistry registerHelpers(String filename, String source) {
		throw closed();
	}

	@Override
	public HelperRegistry registerDecorator(String name, Decorator decorator) {
		throw closed();
	}

	@Override
	public HelperRegistry setCharset(Charset charset) {
		return this;
	}

	private static UnsupportedOperationException closed() {
		return new UnsupportedOperationException("The template helper whitelist is closed (ADR-0011)");
	}

}
