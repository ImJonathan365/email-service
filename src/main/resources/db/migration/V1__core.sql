-- Core schema (docs/06). Executed by email_owner.
-- email_attachment is intentionally not created (ADR-0015 Rejected).

CREATE TABLE tenant (
    id                     uuid PRIMARY KEY,
    slug                   text        NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9-]{2,40}$'),
    name                   text        NOT NULL,
    status                 text        NOT NULL CHECK (status IN ('ACTIVE','SUSPENDED')),
    from_email             text        NOT NULL,
    from_name              text        NOT NULL,
    reply_to               text,
    allowed_from_domains   text[]      NOT NULL DEFAULT '{}',
    allowed_link_hosts     text[]      NOT NULL DEFAULT '{}',
    locale                 text        NOT NULL DEFAULT 'es-CR',
    timezone               text        NOT NULL DEFAULT 'America/Costa_Rica',
    rate_limit_per_minute  int         NOT NULL DEFAULT 60   CHECK (rate_limit_per_minute > 0),
    daily_quota            int         NOT NULL DEFAULT 1000 CHECK (daily_quota > 0),
    retention_days         int         NOT NULL DEFAULT 90   CHECK (retention_days BETWEEN 7 AND 400),
    store_rendered_content boolean     NOT NULL DEFAULT false,
    sending_paused_at      timestamptz,
    pause_reason           text CHECK (pause_reason IN ('VOLUME_ANOMALY','BOUNCE_RATE','COMPLAINT_RATE','MANUAL')),
    anomaly_overrides      jsonb,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    CHECK ((sending_paused_at IS NULL) = (pause_reason IS NULL))
);

CREATE TABLE api_key (
    id            uuid PRIMARY KEY,
    tenant_id     uuid        NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    name          text        NOT NULL,
    key_prefix    text        NOT NULL UNIQUE,
    key_hash      bytea       NOT NULL CHECK (length(key_hash) = 32),
    scopes        text[]      NOT NULL DEFAULT '{emails:send,emails:read}'
                  CHECK (scopes <@ ARRAY['emails:send','emails:read','templates:write','suppressions:write']),
    allowed_cidrs cidr[]      NOT NULL DEFAULT '{}',
    status        text        NOT NULL CHECK (status IN ('ACTIVE','REVOKED')),
    expires_at    timestamptz,
    last_used_at  timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    revoked_at    timestamptz,
    CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL))
);
CREATE INDEX ix_api_key_tenant ON api_key (tenant_id, status);

CREATE TABLE template (
    id               uuid PRIMARY KEY,
    tenant_id        uuid NOT NULL REFERENCES tenant(id),
    key              text NOT NULL CHECK (key ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    name             text NOT NULL,
    description      text,
    category         text NOT NULL CHECK (category IN ('SECURITY','TRANSACTIONAL','NOTICE')),
    tracking_enabled boolean NOT NULL DEFAULT false,
    status           text NOT NULL CHECK (status IN ('ACTIVE','ARCHIVED')),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, key),
    UNIQUE (tenant_id, id),
    CHECK (NOT tracking_enabled OR category = 'NOTICE')
);

CREATE TABLE template_version (
    id                uuid PRIMARY KEY,
    tenant_id         uuid NOT NULL,
    template_id       uuid NOT NULL,
    version           int  NOT NULL CHECK (version >= 1),
    subject_template  text NOT NULL CHECK (length(subject_template) <= 500),
    html_template     text NOT NULL CHECK (octet_length(html_template) <= 262144),
    text_template     text,
    variables_schema  jsonb,
    locale            text NOT NULL DEFAULT 'es-CR' CHECK (locale IN ('es-CR','en')),
    status            text NOT NULL CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED')),
    created_at        timestamptz NOT NULL DEFAULT now(),
    published_at      timestamptz,
    UNIQUE (template_id, version),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, template_id) REFERENCES template (tenant_id, id),
    CHECK (status = 'DRAFT' OR (variables_schema IS NOT NULL AND published_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_tv_published_locale ON template_version (template_id, locale)
    WHERE status = 'PUBLISHED';

-- Messages pin a version at acceptance, so once a version leaves DRAFT its content must never
-- change; the only allowed transition is PUBLISHED -> ARCHIVED (AC-05.2).
CREATE FUNCTION template_version_guard() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, public
AS $$
BEGIN
    IF OLD.status = 'DRAFT' THEN
        RETURN NEW;
    END IF;
    IF (NEW.tenant_id, NEW.template_id, NEW.version, NEW.subject_template, NEW.html_template,
        NEW.text_template, NEW.variables_schema, NEW.locale, NEW.created_at, NEW.published_at)
       IS DISTINCT FROM
       (OLD.tenant_id, OLD.template_id, OLD.version, OLD.subject_template, OLD.html_template,
        OLD.text_template, OLD.variables_schema, OLD.locale, OLD.created_at, OLD.published_at)
    THEN
        RAISE EXCEPTION 'template_version % is immutable once published', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.status <> OLD.status AND NOT (OLD.status = 'PUBLISHED' AND NEW.status = 'ARCHIVED') THEN
        RAISE EXCEPTION 'template_version % cannot change status from % to %', OLD.id, OLD.status, NEW.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_template_version_guard
    BEFORE UPDATE ON template_version
    FOR EACH ROW EXECUTE FUNCTION template_version_guard();

CREATE TABLE email_message (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid        NOT NULL REFERENCES tenant(id),
    idempotency_key      text CHECK (length(idempotency_key) <= 256),
    request_hash         char(64),
    template_id          uuid        NOT NULL,
    template_version_id  uuid        NOT NULL,
    category             text        NOT NULL CHECK (category IN ('SECURITY','TRANSACTIONAL','NOTICE')),
    priority             smallint    NOT NULL CHECK (priority BETWEEN 0 AND 2),
    locale               text        NOT NULL DEFAULT 'es-CR',
    to_email             text        NOT NULL,
    to_name              text,
    cc                   text[] CHECK (cardinality(cc) <= 5),
    bcc                  text[] CHECK (cardinality(bcc) <= 5),
    from_email           text        NOT NULL,
    from_name            text        NOT NULL,
    reply_to             text,
    variables            jsonb       NOT NULL DEFAULT '{}'::jsonb,
    variables_purged_at  timestamptz,
    rendered_subject     text,
    rendered_html        text,
    rendered_text        text,
    status               text        NOT NULL CHECK (status IN
                            ('QUEUED','SENDING','SENT','DELIVERED','BOUNCED','COMPLAINED','FAILED','CANCELED')),
    attempts             int         NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    first_attempt_at     timestamptz,
    next_attempt_at      timestamptz NOT NULL DEFAULT now(),
    send_at              timestamptz,
    lock_token           uuid,
    lock_expires_at      timestamptz,
    locked_by            text,
    provider             text,
    provider_message_id  text,
    failure_code         text,
    failure_detail       text CHECK (length(failure_detail) <= 500),
    attempt_log          jsonb,
    tags                 text[] CHECK (cardinality(tags) <= 10),
    metadata             jsonb CHECK (pg_column_size(metadata) <= 4096),
    tracking_enabled     boolean     NOT NULL DEFAULT false,
    first_opened_at      timestamptz,
    first_clicked_at     timestamptz,
    open_count           int         NOT NULL DEFAULT 0,
    click_count          int         NOT NULL DEFAULT 0,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    sent_at              timestamptz,
    delivered_at         timestamptz,
    finalized_at         timestamptz,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, template_id)         REFERENCES template (tenant_id, id),
    FOREIGN KEY (tenant_id, template_version_id) REFERENCES template_version (tenant_id, id),
    CHECK ((status = 'SENDING') = (lock_token IS NOT NULL AND lock_expires_at IS NOT NULL)),
    CHECK (status NOT IN ('SENT','DELIVERED','BOUNCED','COMPLAINED') OR provider_message_id IS NOT NULL
           OR provider = 'smtp'),
    CHECK (status <> 'FAILED' OR failure_code IS NOT NULL)
);

CREATE INDEX ix_msg_queue ON email_message (priority, next_attempt_at)
    WHERE status = 'QUEUED';
CREATE INDEX ix_msg_stuck ON email_message (lock_expires_at)
    WHERE status = 'SENDING';
CREATE INDEX ix_msg_tenant_created ON email_message (tenant_id, created_at DESC);
CREATE INDEX ix_msg_tenant_status ON email_message (tenant_id, status, created_at DESC);
CREATE INDEX ix_msg_tenant_to ON email_message (tenant_id, lower(to_email), created_at DESC);
CREATE INDEX ix_msg_to_global ON email_message (lower(to_email));
CREATE INDEX ix_msg_purge ON email_message (finalized_at)
    WHERE variables_purged_at IS NULL AND finalized_at IS NOT NULL;
CREATE UNIQUE INDEX uq_msg_provider_id ON email_message (provider, provider_message_id)
    WHERE provider_message_id IS NOT NULL;
CREATE UNIQUE INDEX uq_msg_idempotency ON email_message (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE TABLE email_event (
    id                 uuid PRIMARY KEY,
    tenant_id          uuid REFERENCES tenant(id),
    message_id         uuid,
    provider           text        NOT NULL,
    provider_event_id  text        NOT NULL,
    type               text        NOT NULL CHECK (type IN
                          ('SENT','DELIVERED','DELIVERY_DELAYED','BOUNCED','COMPLAINED','OPENED','CLICKED',
                           'FAILED','SUPPRESSED','OTHER')),
    provider_type      text        NOT NULL,
    occurred_at        timestamptz NOT NULL,
    received_at        timestamptz NOT NULL DEFAULT now(),
    payload            jsonb       NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (provider, provider_event_id),
    FOREIGN KEY (tenant_id, message_id) REFERENCES email_message (tenant_id, id),
    -- A composite FK with a NULL tenant_id is not checked, so a correlated event must carry its tenant.
    CHECK (message_id IS NULL OR tenant_id IS NOT NULL)
);
CREATE INDEX ix_event_message ON email_event (tenant_id, message_id, occurred_at);
CREATE INDEX ix_event_orphan ON email_event (received_at) WHERE message_id IS NULL;

CREATE TABLE suppression (
    id                 uuid PRIMARY KEY,
    scope              text        NOT NULL CHECK (scope IN ('GLOBAL','TENANT')),
    tenant_id          uuid REFERENCES tenant(id),
    email              text CHECK (email = lower(email)),
    email_hash         bytea       NOT NULL CHECK (length(email_hash) = 32),
    reason             text        NOT NULL CHECK (reason IN ('HARD_BOUNCE','COMPLAINT','PROVIDER','MANUAL')),
    source_tenant_id   uuid REFERENCES tenant(id),
    source_message_id  uuid,
    note               text,
    created_at         timestamptz NOT NULL DEFAULT now(),
    CHECK ((scope = 'TENANT') = (tenant_id IS NOT NULL)),
    CHECK ((reason = 'MANUAL') = (scope = 'TENANT'))
);
CREATE UNIQUE INDEX uq_supp_global ON suppression (email_hash) WHERE scope = 'GLOBAL';
CREATE UNIQUE INDEX uq_supp_tenant ON suppression (tenant_id, email_hash) WHERE scope = 'TENANT';

CREATE TABLE audit_log (
    id             uuid PRIMARY KEY,
    tenant_id      uuid REFERENCES tenant(id),
    actor_type     text        NOT NULL CHECK (actor_type IN ('ADMIN','API_KEY','SYSTEM')),
    actor_id       text,
    action         text        NOT NULL,
    resource_type  text,
    resource_id    text,
    ip             inet,
    request_id     text,
    metadata       jsonb,
    created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_tenant_created ON audit_log (tenant_id, created_at DESC);

CREATE TABLE rate_limit_counter (
    tenant_id     uuid        NOT NULL REFERENCES tenant(id),
    window_kind   text        NOT NULL CHECK (window_kind IN ('MINUTE','DAY')),
    window_start  timestamptz NOT NULL,
    count         int         NOT NULL DEFAULT 0 CHECK (count >= 0),
    PRIMARY KEY (tenant_id, window_kind, window_start)
);
