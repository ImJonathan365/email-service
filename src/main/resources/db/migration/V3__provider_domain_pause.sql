-- A tenant whose sender domain the provider reports as unverified is paused automatically
-- (PROVIDER_DOMAIN) until an administrator resumes it. Widening the CHECK keeps the migration
-- backward compatible with the running version (NFR-09).
ALTER TABLE tenant
    DROP CONSTRAINT tenant_pause_reason_check,
    ADD CONSTRAINT tenant_pause_reason_check
        CHECK (pause_reason IN ('VOLUME_ANOMALY','BOUNCE_RATE','COMPLAINT_RATE','MANUAL','PROVIDER_DOMAIN'));
