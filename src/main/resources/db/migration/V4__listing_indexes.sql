-- GET /v1/emails (AC-20.2). Under RLS PostgreSQL only uses an index for leakproof conditions:
-- texteq, uuid_eq and timestamp comparisons are; lower() and the array operator @> are not, so
-- the "to" and "tag" filters scanned the whole tenant. Both become plain text equalities here.

-- Recipient filter: the lower-cased address as a stored column, compared with texteq.
ALTER TABLE email_message
    ADD COLUMN to_email_lower text GENERATED ALWAYS AS (lower(to_email)) STORED;

DROP INDEX ix_msg_tenant_to;
DROP INDEX ix_msg_to_global;
CREATE INDEX ix_msg_tenant_to ON email_message (tenant_id, to_email_lower, created_at DESC, id DESC);
CREATE INDEX ix_msg_to_global ON email_message (to_email_lower);

-- Keyset pagination orders by (created_at, id): the id tiebreak belongs in the index.
DROP INDEX ix_msg_tenant_created;
DROP INDEX ix_msg_tenant_status;
CREATE INDEX ix_msg_tenant_created ON email_message (tenant_id, created_at DESC, id DESC);
CREATE INDEX ix_msg_tenant_status ON email_message (tenant_id, status, created_at DESC, id DESC);
CREATE INDEX ix_msg_tenant_template ON email_message (tenant_id, template_id, created_at DESC, id DESC);

-- Tag filter: one row per (message, tag), written by a trigger in the INSERT's transaction and
-- deleted with the message. Tags never change after acceptance.
CREATE TABLE email_message_tag (
    tenant_id   uuid        NOT NULL,
    message_id  uuid        NOT NULL,
    tag         text        NOT NULL,
    created_at  timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, message_id, tag),
    FOREIGN KEY (tenant_id, message_id) REFERENCES email_message (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX ix_msg_tag ON email_message_tag (tenant_id, tag, created_at DESC, message_id DESC);

ALTER TABLE email_message_tag ENABLE ROW LEVEL SECURITY;
ALTER TABLE email_message_tag FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON email_message_tag
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, DELETE ON email_message_tag TO email_app, email_system;

-- Runs with the inserting role's rights, so RLS checks the tag rows like the message itself.
CREATE FUNCTION email_message_tags_insert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.tags IS NOT NULL AND cardinality(NEW.tags) > 0 THEN
        INSERT INTO email_message_tag (tenant_id, message_id, tag, created_at)
        SELECT DISTINCT NEW.tenant_id, NEW.id, tag, NEW.created_at FROM unnest(NEW.tags) AS tag;
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER email_message_tags_insert
    AFTER INSERT ON email_message
    FOR EACH ROW EXECUTE FUNCTION email_message_tags_insert();

INSERT INTO email_message_tag (tenant_id, message_id, tag, created_at)
SELECT DISTINCT m.tenant_id, m.id, tag, m.created_at
FROM email_message m CROSS JOIN LATERAL unnest(m.tags) AS tag;
