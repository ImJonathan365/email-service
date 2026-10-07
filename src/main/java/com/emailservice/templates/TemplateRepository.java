package com.emailservice.templates;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * template and template_version through the tenant DataSource (email_app, RLS). Every query also
 * filters by tenant_id: RLS is the second barrier, not the first (NFR-08). Callers run inside a
 * tenant transaction.
 */
@Repository
class TemplateRepository {

	private static final String TEMPLATE_COLUMNS = """
			id, key, name, description, category, tracking_enabled, status, created_at, updated_at""";

	private static final String VERSION_COLUMNS = """
			id, template_id, version, locale, status, subject_template, html_template, text_template,
			variables_schema::text AS variables_schema, created_at, published_at""";

	private final JdbcClient jdbc;

	TemplateRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	JdbcClient jdbc() {
		return jdbc;
	}

	/** Empty when the key is already used by this tenant. */
	Optional<TemplateViews.StoredTemplate> insert(UUID tenantId, TemplateViews.CreateTemplate request) {
		return jdbc.sql("""
				INSERT INTO template (id, tenant_id, key, name, description, category, tracking_enabled, status)
				VALUES (:id, :tenantId, :key, :name, :description, :category, :tracking, 'ACTIVE')
				ON CONFLICT (tenant_id, key) DO NOTHING
				RETURNING
				""" + TEMPLATE_COLUMNS)
			.param("id", UUID.randomUUID())
			.param("tenantId", tenantId)
			.param("key", request.key())
			.param("name", request.name())
			.param("description", request.description())
			.param("category", request.category())
			.param("tracking", Boolean.TRUE.equals(request.trackingEnabled()))
			.query(TemplateRepository::template)
			.optional();
	}

	List<TemplateViews.StoredTemplate> findAll(UUID tenantId) {
		return jdbc.sql("SELECT " + TEMPLATE_COLUMNS + " FROM template WHERE tenant_id = :tenantId ORDER BY key")
			.param("tenantId", tenantId)
			.query(TemplateRepository::template)
			.list();
	}

	Optional<TemplateViews.StoredTemplate> findByKey(UUID tenantId, String key) {
		return jdbc.sql("SELECT " + TEMPLATE_COLUMNS + " FROM template WHERE tenant_id = :tenantId AND key = :key")
			.param("tenantId", tenantId)
			.param("key", key)
			.query(TemplateRepository::template)
			.optional();
	}

	/** Locks the template row so version numbering and publication are serialized per template. */
	Optional<TemplateViews.StoredTemplate> lockByKey(UUID tenantId, String key) {
		return jdbc
			.sql("SELECT " + TEMPLATE_COLUMNS + " FROM template WHERE tenant_id = :tenantId AND key = :key FOR UPDATE")
			.param("tenantId", tenantId)
			.param("key", key)
			.query(TemplateRepository::template)
			.optional();
	}

	List<TemplateViews.StoredVersion> versions(UUID tenantId, UUID templateId) {
		return jdbc.sql("SELECT " + VERSION_COLUMNS + """
				 FROM template_version WHERE tenant_id = :tenantId AND template_id = :templateId ORDER BY version
				""")
			.param("tenantId", tenantId)
			.param("templateId", templateId)
			.query(TemplateRepository::version)
			.list();
	}

	Optional<TemplateViews.StoredVersion> version(UUID tenantId, UUID templateId, int version) {
		return jdbc.sql("SELECT " + VERSION_COLUMNS + """
				 FROM template_version WHERE tenant_id = :tenantId AND template_id = :templateId AND version = :version
				""")
			.param("tenantId", tenantId)
			.param("templateId", templateId)
			.param("version", version)
			.query(TemplateRepository::version)
			.optional();
	}

	/** Published versions of all the tenant's templates, for the list view. */
	List<PublishedRow> publishedVersions(UUID tenantId) {
		return jdbc.sql("""
				SELECT template_id, version, locale FROM template_version
				WHERE tenant_id = :tenantId AND status = 'PUBLISHED' ORDER BY locale
				""")
			.param("tenantId", tenantId)
			.query((rs, row) -> new PublishedRow(rs.getObject("template_id", UUID.class),
					new TemplateViews.PublishedVersion(rs.getInt("version"), rs.getString("locale"))))
			.list();
	}

	record PublishedRow(UUID templateId, TemplateViews.PublishedVersion version) {
	}

	TemplateViews.StoredVersion insertDraft(UUID tenantId, UUID templateId, TemplateViews.VersionContent content,
			String locale, String variablesSchema) {
		return jdbc.sql("""
				INSERT INTO template_version (id, tenant_id, template_id, version, subject_template, html_template,
				    text_template, variables_schema, locale, status)
				SELECT :id, :tenantId, :templateId, coalesce(max(version), 0) + 1, :subject, :html, :text,
				       CAST(:schema AS jsonb), :locale, 'DRAFT'
				FROM template_version WHERE tenant_id = :tenantId AND template_id = :templateId
				RETURNING
				""" + VERSION_COLUMNS)
			.param("id", UUID.randomUUID())
			.param("tenantId", tenantId)
			.param("templateId", templateId)
			.param("subject", content.subjectTemplate())
			.param("html", content.htmlTemplate())
			.param("text", content.textTemplate())
			.param("schema", variablesSchema)
			.param("locale", locale)
			.query(TemplateRepository::version)
			.single();
	}

	TemplateViews.StoredVersion updateDraft(UUID tenantId, UUID versionId, TemplateViews.VersionContent content,
			String locale, String variablesSchema) {
		return jdbc.sql("""
				UPDATE template_version SET subject_template = :subject, html_template = :html, text_template = :text,
				    variables_schema = CAST(:schema AS jsonb), locale = :locale
				WHERE tenant_id = :tenantId AND id = :id AND status = 'DRAFT'
				RETURNING
				""" + VERSION_COLUMNS)
			.param("tenantId", tenantId)
			.param("id", versionId)
			.param("subject", content.subjectTemplate())
			.param("html", content.htmlTemplate())
			.param("text", content.textTemplate())
			.param("schema", variablesSchema)
			.param("locale", locale)
			.query(TemplateRepository::version)
			.single();
	}

	void deleteDraft(UUID tenantId, UUID versionId) {
		jdbc.sql("DELETE FROM template_version WHERE tenant_id = :tenantId AND id = :id AND status = 'DRAFT'")
			.param("tenantId", tenantId)
			.param("id", versionId)
			.update();
	}

	void archivePublished(UUID tenantId, UUID templateId, String locale) {
		jdbc.sql("""
				UPDATE template_version SET status = 'ARCHIVED'
				WHERE tenant_id = :tenantId AND template_id = :templateId AND locale = :locale AND status = 'PUBLISHED'
				""")
			.param("tenantId", tenantId)
			.param("templateId", templateId)
			.param("locale", locale)
			.update();
	}

	TemplateViews.StoredVersion publish(UUID tenantId, UUID versionId) {
		return jdbc.sql("""
				UPDATE template_version SET status = 'PUBLISHED', published_at = now()
				WHERE tenant_id = :tenantId AND id = :id AND status = 'DRAFT'
				RETURNING
				""" + VERSION_COLUMNS)
			.param("tenantId", tenantId)
			.param("id", versionId)
			.query(TemplateRepository::version)
			.single();
	}

	private static TemplateViews.StoredTemplate template(ResultSet rs, int row) throws SQLException {
		return new TemplateViews.StoredTemplate(rs.getObject("id", UUID.class),
				new TemplateViews.Template(rs.getString("key"), rs.getString("name"), rs.getString("description"),
						rs.getString("category"), rs.getBoolean("tracking_enabled"), rs.getString("status"),
						instant(rs, "created_at"), instant(rs, "updated_at")));
	}

	private static TemplateViews.StoredVersion version(ResultSet rs, int row) throws SQLException {
		return new TemplateViews.StoredVersion(rs.getObject("id", UUID.class), rs.getObject("template_id", UUID.class),
				rs.getInt("version"), rs.getString("locale"), rs.getString("status"), rs.getString("subject_template"),
				rs.getString("html_template"), rs.getString("text_template"), rs.getString("variables_schema"),
				instant(rs, "created_at"), instant(rs, "published_at"));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

}
