package com.emailservice.tenancy.admin;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.emailservice.audit.Actor;
import com.emailservice.audit.AuditAction;
import com.emailservice.audit.AuditLog;
import com.emailservice.common.api.ApiException;
import com.emailservice.common.api.ErrorCode;
import com.emailservice.common.api.Problem.FieldError;
import com.emailservice.common.config.AppProperties;

/** Tenant administration (FR-02). Every change is audited in the same transaction (FR-22). */
@Service
class TenantAdminService {

	static final String DEFAULT_LOCALE = "es-CR";

	static final String DEFAULT_TIMEZONE = "America/Costa_Rica";

	private static final Pattern HOST = Pattern
		.compile("(?=.{1,253}$)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+");

	private final TenantRepository tenants;

	private final AuditLog auditLog;

	private final AppProperties.Tenancy settings;

	TenantAdminService(TenantRepository tenants, AuditLog auditLog, AppProperties properties) {
		this.tenants = tenants;
		this.auditLog = auditLog;
		this.settings = properties.tenancy();
	}

	@Transactional("systemTransactionManager")
	TenantView create(TenantRequests.Create request, Actor actor) {
		List<FieldError> errors = new ArrayList<>();
		List<String> fromDomains = hosts("allowedFromDomains", request.allowedFromDomains(), errors);
		List<String> linkHosts = hosts("allowedLinkHosts", request.allowedLinkHosts(), errors);
		String locale = request.locale() == null ? DEFAULT_LOCALE : request.locale();
		String timezone = request.timezone() == null ? DEFAULT_TIMEZONE : request.timezone();
		validateLocale(locale, errors);
		validateTimezone(timezone, errors);
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}

		var tenant = new TenantRepository.NewTenant(request.slug(), request.name(), request.fromEmail(),
				request.fromName(), request.replyTo(), fromDomains, linkHosts, locale, timezone,
				orDefault(request.rateLimitPerMinute(), settings.defaultRateLimitPerMinute()),
				orDefault(request.dailyQuota(), settings.defaultDailyQuota()),
				orDefault(request.retentionDays(), settings.defaultRetentionDays()),
				Boolean.TRUE.equals(request.storeRenderedContent()));
		TenantView created;
		try {
			created = tenants.insert(UUID.randomUUID(), tenant);
		}
		catch (DuplicateKeyException ex) {
			throw new ApiException(ErrorCode.TENANT_SLUG_TAKEN, "Tenant slug '" + request.slug() + "' is in use.");
		}
		auditLog.record(tenants.jdbc(), actor, AuditAction.TENANT_CREATED, created.id(), "tenant",
				created.id().toString(), Map.of("slug", created.slug()));
		return created;
	}

	@Transactional(transactionManager = "systemTransactionManager", readOnly = true)
	List<TenantView> list() {
		return tenants.findAll();
	}

	@Transactional("systemTransactionManager")
	TenantView update(String slug, TenantRequests.Update request, Actor actor) {
		TenantView current = tenants.findBySlugForUpdate(slug)
			.orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Tenant '" + slug + "' not found."));

		List<FieldError> errors = new ArrayList<>();
		List<String> fromDomains = request.allowedFromDomains() == null ? null
				: hosts("allowedFromDomains", request.allowedFromDomains(), errors);
		List<String> linkHosts = request.allowedLinkHosts() == null ? null
				: hosts("allowedLinkHosts", request.allowedLinkHosts(), errors);
		if (request.locale() != null) {
			validateLocale(request.locale(), errors);
		}
		if (request.timezone() != null) {
			validateTimezone(request.timezone(), errors);
		}
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}

		var normalized = new TenantRequests.Update(request.name(), request.fromEmail(), request.fromName(),
				request.replyTo(), fromDomains, linkHosts, request.locale(), request.timezone(),
				request.rateLimitPerMinute(), request.dailyQuota(), request.retentionDays(),
				request.storeRenderedContent(), request.status());
		TenantView updated = tenants.update(current.id(), normalized);

		List<String> changedFields = changedFields(request);
		if (!changedFields.isEmpty()) {
			auditLog.record(tenants.jdbc(), actor, AuditAction.TENANT_UPDATED, current.id(), "tenant",
					current.id().toString(), Map.of("fields", changedFields));
		}
		if (!updated.status().equals(current.status())) {
			AuditAction action = "SUSPENDED".equals(updated.status()) ? AuditAction.TENANT_SUSPENDED
					: AuditAction.TENANT_REACTIVATED;
			auditLog.record(tenants.jdbc(), actor, action, current.id(), "tenant", current.id().toString(), null);
		}
		return updated;
	}

	private void validateLocale(String locale, List<FieldError> errors) {
		if (!settings.supportedLocales().contains(locale)) {
			errors.add(new FieldError("locale", "must be one of " + settings.supportedLocales()));
		}
	}

	private static void validateTimezone(String timezone, List<FieldError> errors) {
		try {
			ZoneId.of(timezone);
		}
		catch (DateTimeException ex) {
			errors.add(new FieldError("timezone", "must be an IANA time zone such as America/Costa_Rica"));
		}
	}

	private static List<String> hosts(String field, List<String> values, List<FieldError> errors) {
		if (values == null) {
			return List.of();
		}
		List<String> normalized = new ArrayList<>();
		for (int i = 0; i < values.size(); i++) {
			String value = values.get(i) == null ? "" : values.get(i).trim().toLowerCase(Locale.ROOT);
			if (HOST.matcher(value).matches()) {
				normalized.add(value);
			}
			else {
				errors.add(new FieldError(field + "[" + i + "]", "must be a host name such as app.example.com"));
			}
		}
		return normalized.stream().distinct().toList();
	}

	private static List<String> changedFields(TenantRequests.Update request) {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("name", request.name());
		fields.put("fromEmail", request.fromEmail());
		fields.put("fromName", request.fromName());
		fields.put("replyTo", request.replyTo());
		fields.put("allowedFromDomains", request.allowedFromDomains());
		fields.put("allowedLinkHosts", request.allowedLinkHosts());
		fields.put("locale", request.locale());
		fields.put("timezone", request.timezone());
		fields.put("rateLimitPerMinute", request.rateLimitPerMinute());
		fields.put("dailyQuota", request.dailyQuota());
		fields.put("retentionDays", request.retentionDays());
		fields.put("storeRenderedContent", request.storeRenderedContent());
		return fields.entrySet().stream().filter(e -> e.getValue() != null).map(Map.Entry::getKey).toList();
	}

	private static int orDefault(Integer value, int fallback) {
		return value == null ? fallback : value;
	}

}
