-- Row Level Security and grants (ADR-0008). Executed by email_owner.
-- Policies are fail-closed: without app.tenant_id no row is visible or writable.
-- nullif() matters on pooled connections: once a transaction-local set_config() ends, the setting
-- reads as '' instead of NULL, and ''::uuid would raise instead of matching nothing.

ALTER TABLE tenant ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_self ON tenant FOR SELECT
    USING (id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE api_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE api_key FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON api_key
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE template ENABLE ROW LEVEL SECURITY;
ALTER TABLE template FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON template
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE template_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE template_version FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON template_version
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE email_message ENABLE ROW LEVEL SECURITY;
ALTER TABLE email_message FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON email_message
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE email_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE email_event FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON email_event
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE rate_limit_counter ENABLE ROW LEVEL SECURITY;
ALTER TABLE rate_limit_counter FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON rate_limit_counter
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_log FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit_log
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Tenants see their own suppressions and the global ones, and only write their own (ADR-0012).
ALTER TABLE suppression ENABLE ROW LEVEL SECURITY;
ALTER TABLE suppression FORCE  ROW LEVEL SECURITY;
CREATE POLICY supp_read ON suppression FOR SELECT
    USING (scope = 'GLOBAL' OR tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY supp_write ON suppression FOR INSERT
    WITH CHECK (scope = 'TENANT' AND tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY supp_delete ON suppression FOR DELETE
    USING (scope = 'TENANT' AND tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT USAGE ON SCHEMA public TO email_app, email_system;

GRANT SELECT, INSERT, UPDATE, DELETE
    ON api_key, template, template_version, email_message, email_event, rate_limit_counter
    TO email_app, email_system;

-- Append-only for both runtime roles; purging is done by an owner function (H7).
GRANT SELECT, INSERT ON audit_log TO email_app, email_system;

-- Tenants are administered only through email_system; the app reads its own row.
GRANT SELECT ON tenant TO email_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant TO email_system;

-- source_* columns are hidden from tenants so a global suppression does not reveal which
-- tenant originated it.
GRANT SELECT (id, scope, tenant_id, email, email_hash, reason, note, created_at),
      INSERT (id, scope, tenant_id, email, email_hash, reason, note, created_at),
      DELETE
    ON suppression TO email_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON suppression TO email_system;
