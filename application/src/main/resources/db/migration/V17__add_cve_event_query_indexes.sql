-- -----------------------------------------------------------------------------
-- CVE-Bot: query-shaped indexes for /latest and the summary worker
-- -----------------------------------------------------------------------------
-- Rationale:
--   findRecentDoneEvents (/latest) filters topic_id IN (...) AND summary_status
--   = 'DONE' ordered by id DESC; once most rows are DONE the status-keyed
--   indexes from V14/V16 scan unrelated topics before reaching the caller's.
--   (topic_id, summary_status, id) serves both the filter and the id order.
--
--   findClaimable filters summary_status IN ('PENDING', 'FAILED') with a
--   next_attempt_at due check; (summary_status, next_attempt_at) keeps that
--   scan off the DONE majority without touching rows.
--
-- Apply BEFORE rolling out in any environment with `ddl-auto: none` (prod).
-- dev/local with auto-ddl pick this up automatically.
-- -----------------------------------------------------------------------------

DROP INDEX IF EXISTS idx_cve_event_topic_status_id ON cve_event;
CREATE INDEX idx_cve_event_topic_status_id ON cve_event (topic_id, summary_status, id);

DROP INDEX IF EXISTS idx_cve_event_status_next_attempt ON cve_event;
CREATE INDEX idx_cve_event_status_next_attempt ON cve_event (summary_status, next_attempt_at);
