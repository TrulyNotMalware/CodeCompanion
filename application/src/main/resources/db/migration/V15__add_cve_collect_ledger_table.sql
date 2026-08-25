-- -----------------------------------------------------------------------------
-- CVE-Bot: feed collector once-per-window claim ledger (schema for M4)
-- -----------------------------------------------------------------------------
-- Rationale:
--   The feed collector polls each active topic on a fixed schedule. With more
--   than one app instance, every instance would fetch the same topic on the same
--   tick. This ledger is the multi-instance dedup gate: an instance claims a
--   (topic, window) row with INSERT IGNORE before fetching, and only the instance
--   that inserts the row (affected rows = 1) collects that window. Event-level
--   idempotency is separate (cve_event unique(topic_id, external_id)).
--
-- Behaviour:
--   - One row per (topic_id, window_start); the unique key enforces once-per-window.
--   - window_start is the tick time truncated to the collect interval bucket.
--   - No mutable state and no claim token — a window is either claimed or not.
--
-- Apply BEFORE rolling out the collector in any environment with
-- `ddl-auto: none` (prod). dev/local with auto-ddl pick this up automatically.
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS cve_collect_ledger (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    topic_id     BIGINT       NOT NULL,
    window_start DATETIME(6)  NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    CONSTRAINT uk_cve_collect_ledger_topic_window UNIQUE (topic_id, window_start)
);
