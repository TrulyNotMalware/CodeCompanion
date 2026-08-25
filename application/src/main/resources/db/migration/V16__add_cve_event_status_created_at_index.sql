-- -----------------------------------------------------------------------------
-- CVE-Bot: notification dispatcher lookup index (schema for M5)
-- -----------------------------------------------------------------------------
-- Rationale:
--   The notification dispatcher's findUndelivered anti-join scans cve_event for
--   summary_status = DONE within a created_at delivery horizon. cve_event has no
--   TTL, so over time the single-column summary_status index turns non-selective
--   (most rows end up DONE). This composite index keys the scan on
--   (summary_status, created_at) so the horizon bound stays index-served.
--
-- Apply BEFORE rolling out the M5 dispatcher in any environment with
-- `ddl-auto: none` (prod). dev/local with auto-ddl pick this up automatically.
-- -----------------------------------------------------------------------------

DROP INDEX IF EXISTS idx_cve_event_status_created_at ON cve_event;
CREATE INDEX idx_cve_event_status_created_at ON cve_event (summary_status, created_at);
