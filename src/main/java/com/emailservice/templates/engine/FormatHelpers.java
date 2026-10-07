package com.emailservice.templates.engine;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
import java.util.Currency;
import java.util.Locale;
import java.util.Set;

import com.github.jknack.handlebars.Helper;
import com.github.jknack.handlebars.Options;

/**
 * formatDate, formatNumber and formatMoney (ADR-0011, AC-37.5). They format with the message's
 * effective locale and the tenant's time zone, passed as render data; nothing reads the clock, so
 * a render is deterministic. Error messages never include the variable's value.
 */
final class FormatHelpers {

	static final String LOCALE = "emailLocale";

	static final String ZONE = "emailZone";

	static final Set<String> CURRENCIES = Set.of("CRC", "USD");

	private FormatHelpers() {
	}

	/** {{formatDate value [short|medium|long|full|pattern]}}; value is ISO-8601, default medium. */
	static final Helper<Object> FORMAT_DATE = (value, options) -> {
		Locale locale = locale(options);
		ZoneId zone = options.data(ZONE);
		String style = options.params.length > 0 ? String.valueOf(options.params[0]) : "medium";
		String text = String.valueOf(value);
		try {
			LocalDate date = LocalDate.parse(text);
			return date.format(dateFormatter(style, locale, false));
		}
		catch (DateTimeParseException notADate) {
			ZonedDateTime dateTime = parseDateTime(text).atZone(zone);
			return dateTime.format(dateFormatter(style, locale, true));
		}
	};

	/** {{formatNumber value [decimals]}}. */
	static final Helper<Object> FORMAT_NUMBER = (value, options) -> {
		NumberFormat format = NumberFormat.getNumberInstance(locale(options));
		if (options.params.length > 0) {
			int decimals = decimals(options.params[0]);
			format.setMinimumFractionDigits(decimals);
			format.setMaximumFractionDigits(decimals);
		}
		return format.format(number(value, "formatNumber"));
	};

	/** {{formatMoney value currency}} with currency CRC or USD. */
	static final Helper<Object> FORMAT_MONEY = (value, options) -> {
		String code = options.params.length > 0 ? String.valueOf(options.params[0]) : "";
		if (!CURRENCIES.contains(code)) {
			throw new IllegalArgumentException("formatMoney needs the currency CRC or USD");
		}
		NumberFormat format = NumberFormat.getCurrencyInstance(locale(options));
		format.setCurrency(Currency.getInstance(code));
		return format.format(number(value, "formatMoney"));
	};

	private static Locale locale(Options options) {
		return Locale.forLanguageTag(options.data(LOCALE));
	}

	private static Instant parseDateTime(String text) {
		try {
			return OffsetDateTime.parse(text).toInstant();
		}
		catch (DateTimeParseException ex) {
			throw new IllegalArgumentException("formatDate needs an ISO-8601 date or date-time");
		}
	}

	private static DateTimeFormatter dateFormatter(String style, Locale locale, boolean withTime) {
		FormatStyle formatStyle = switch (style) {
			case "short" -> FormatStyle.SHORT;
			case "medium" -> FormatStyle.MEDIUM;
			case "long" -> FormatStyle.LONG;
			case "full" -> FormatStyle.FULL;
			default -> null;
		};
		if (formatStyle == null) {
			try {
				return DateTimeFormatter.ofPattern(style, locale);
			}
			catch (IllegalArgumentException ex) {
				throw new IllegalArgumentException("formatDate has an invalid pattern");
			}
		}
		return (withTime ? DateTimeFormatter.ofLocalizedDateTime(formatStyle, FormatStyle.SHORT)
				: DateTimeFormatter.ofLocalizedDate(formatStyle))
			.withLocale(locale);
	}

	private static BigDecimal number(Object value, String helper) {
		if (value instanceof Number number) {
			return new BigDecimal(number.toString());
		}
		try {
			return new BigDecimal(String.valueOf(value));
		}
		catch (NumberFormatException ex) {
			throw new IllegalArgumentException(helper + " needs a number");
		}
	}

	private static int decimals(Object value) {
		int decimals;
		try {
			decimals = Integer.parseInt(String.valueOf(value));
		}
		catch (NumberFormatException ex) {
			throw new IllegalArgumentException("formatNumber decimals must be an integer");
		}
		if (decimals < 0 || decimals > 6) {
			throw new IllegalArgumentException("formatNumber decimals must be between 0 and 6");
		}
		return decimals;
	}

}
