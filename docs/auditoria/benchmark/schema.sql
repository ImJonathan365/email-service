DROP TABLE IF EXISTS email_message, rate_limit_counter;
CREATE TABLE rate_limit_counter(tenant_id uuid, window_kind text, window_start timestamptz, count int, PRIMARY KEY(tenant_id,window_kind,window_start));
CREATE TABLE email_message(
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, idempotency_key text, request_hash char(64),
 template_id uuid NOT NULL, template_version_id uuid NOT NULL, to_email text NOT NULL, to_name text,
 from_email text NOT NULL, from_name text NOT NULL, variables jsonb NOT NULL, status text NOT NULL,
 attempts int NOT NULL DEFAULT 0, next_attempt_at timestamptz NOT NULL DEFAULT now(), lock_expires_at timestamptz,
 provider_message_id text, provider text, tags text[], metadata jsonb,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX ix_msg_queue ON email_message (next_attempt_at) WHERE status='QUEUED';
CREATE INDEX ix_msg_tenant_created ON email_message (tenant_id, created_at DESC);
CREATE INDEX ix_msg_tenant_status ON email_message (tenant_id, status, created_at DESC);
CREATE INDEX ix_msg_tenant_to ON email_message (tenant_id, lower(to_email), created_at DESC);
CREATE UNIQUE INDEX uq_msg_provider_id ON email_message (provider, provider_message_id) WHERE provider_message_id IS NOT NULL;
CREATE UNIQUE INDEX uq_msg_idempotency ON email_message (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
