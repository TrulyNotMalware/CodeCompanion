-- -----------------------------------------------------------------------------
-- Phase 4 #14: Meeting Reminder — idempotency table + scheduling claim token
-- -----------------------------------------------------------------------------
-- Rationale:
--   Before each meeting starts, the reminder scheduler DMs every attending
--   participant at the configured offsets (15 + 5 minutes before start_at). This
--   table is the idempotency + claim ledger that makes that restart-safe: one row
--   per (meeting_id, offset_minutes) so a duplicate tick or a restart re-materializes
--   to the existing row instead of double-sending. `claim_token` narrows the
--   scheduler's atomic CAS to its own claim, exactly like standup_session_dispatch.
--
-- Behaviour:
--   - The unique key (meeting_id, offset_minutes) enforces "one reminder per meeting
--     per offset" at the DB level — the core idempotency guarantee.
--   - scheduled_at = start_at - offset_minutes (the absolute fire time), so the
--     dispatch sweep selects PENDING rows by `scheduled_at <= now` without re-deriving.
--   - FK to meetings(id) with ON DELETE CASCADE keeps the ledger consistent with its
--     parent meeting.
--
-- Apply BEFORE rolling out the reminder application code in any environment with
-- `ddl-auto: none` (prod). dev/local with auto-ddl pick this up automatically.
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS meeting_reminder (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    meeting_id      BIGINT       NOT NULL,
    offset_minutes  INT          NOT NULL,
    scheduled_at    DATETIME     NOT NULL,
    sent_at         DATETIME     NULL,
    status          VARCHAR(16)  NOT NULL,
    failure_reason  TEXT         NULL,
    claim_token     VARCHAR(36)  NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NULL ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_meeting_reminder_meeting_offset UNIQUE (meeting_id, offset_minutes),
    CONSTRAINT fk_meeting_reminder_meeting
        FOREIGN KEY (meeting_id) REFERENCES meetings (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_meeting_reminder_scheduled_status
    ON meeting_reminder (status, scheduled_at);
