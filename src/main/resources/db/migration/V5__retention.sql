-- Retention purges (FR-23): partial indexes so each purge reads only its candidates, and the
-- audit_log purge, which the runtime roles cannot do themselves (append-only, docs/06 §3.8).

-- purgeVariables: SENT/DELIVERED messages become final by time (SENT_FINAL_AFTER_HOURS).
CREATE INDEX ix_msg_purge_by_time ON email_message (sent_at)
    WHERE variables_purged_at IS NULL AND status IN ('SENT', 'DELIVERED');
-- purgeIdempotencyKeys.
CREATE INDEX ix_msg_idempotency_age ON email_message (created_at) WHERE idempotency_key IS NOT NULL;
-- Rendered content, emptied after 30 days.
CREATE INDEX ix_msg_rendered_age ON email_message (created_at)
    WHERE rendered_subject IS NOT NULL OR rendered_html IS NOT NULL OR rendered_text IS NOT NULL;
-- Retention by tenant and the status metrics, both across tenants by age.
CREATE INDEX ix_msg_created ON email_message (created_at);

-- audit_log keeps 365 days (AC-23.4). email_owner is subject to FORCE RLS, so it gets a policy
-- that shows it only the expired rows, and the purge runs through this function alone: the
-- worker (email_system) may call it, never delete audit rows directly.
CREATE POLICY audit_retention ON audit_log FOR ALL TO email_owner
    USING (created_at < now() - interval '365 days');

CREATE FUNCTION purge_expired_audit_log(batch_size int) RETURNS int
    LANGUAGE sql SECURITY DEFINER SET search_path = public, pg_temp AS $$
    WITH expired AS (
        SELECT id FROM audit_log WHERE created_at < now() - interval '365 days' LIMIT batch_size
    ), deleted AS (
        DELETE FROM audit_log a USING expired e WHERE a.id = e.id RETURNING 1
    )
    SELECT count(*)::int FROM deleted;
$$;

REVOKE ALL ON FUNCTION purge_expired_audit_log(int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION purge_expired_audit_log(int) TO email_system;
