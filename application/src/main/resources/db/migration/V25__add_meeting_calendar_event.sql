-- -----------------------------------------------------------------------------
-- Google Calendar mirror: per-(meeting, user) sync queue
-- -----------------------------------------------------------------------------
-- Rationale:
--   A user who connected Google Calendar (V24) gets a copy of every meeting they
--   host or accepted in their own primary calendar. The Google call cannot run
--   inside the meeting write's transaction, so each (meeting, user) pair gets one
--   row here that the sync scheduler claims with a CAS, like meeting_reminder (V5).
--
-- Behaviour:
--   - The row is a dirty marker, not a command: it means "look at this
--     (meeting, user) again", never "insert" or "delete". The worker recomputes the
--     desired state from meetings / meeting_participants when it runs: a canceled
--     meeting, or a user who is neither the host nor attending, must have no event
--     (the row is deleted once Google confirms); anything else must have an event
--     that matches the meeting. Trusting a queued action would leave a canceled
--     meeting's event behind when a cancel and an accept commit around each other.
--   - change_seq is a generation counter. Every hook bumps it and sets PENDING
--     unless a worker holds the row as SYNCING. The worker remembers the value it
--     claimed; its completion CAS writes SYNCED only when change_seq is unchanged and
--     PENDING otherwise, and stores google_event_id in both cases, so a change that
--     arrives while the Google call is in flight is synced again and the event the
--     call created is never orphaned.
--   - status: PENDING (due at next_attempt_at), SYNCING (claimed; claim_token names
--     the owner), SYNCED, FAILED (retries exhausted, the user's grant was revoked,
--     the user is not connected, or the user disconnected). Every native
--     transition binds updated_at from the application clock; the stuck-SYNCING
--     sweep compares against that value.
--   - Timestamps are DATETIME(6) and carry no database default or ON UPDATE clause,
--     like V24: every insert (JPA or the native enqueue upsert) binds created_at, and
--     every transition binds updated_at, so the database clock never writes either.
--   - One row per (meeting_id, slack_user_id); FK to meetings(id) with
--     ON DELETE CASCADE keeps the queue consistent with its parent meeting.
--
-- Apply BEFORE rolling out the calendar mirror code in any environment with
-- `ddl-auto: none` (prod), after V24. dev/local with auto-ddl pick this up
-- automatically. Nothing in the previous release reads or writes this table, so
-- the script is safe to apply while it still runs; rollback is DROP TABLE.
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS meeting_calendar_event (
    id              BIGINT        AUTO_INCREMENT PRIMARY KEY,
    meeting_id      BIGINT        NOT NULL,
    slack_user_id   VARCHAR(64)   NOT NULL,
    google_event_id VARCHAR(1024) NULL,
    status          VARCHAR(16)   NOT NULL,
    change_seq      BIGINT        NOT NULL DEFAULT 1,
    attempts        INT           NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6)   NOT NULL,
    claim_token     VARCHAR(36)   NULL,
    last_error      TEXT          NULL,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NULL,
    CONSTRAINT uk_meeting_calendar_event_meeting_user UNIQUE (meeting_id, slack_user_id),
    CONSTRAINT fk_meeting_calendar_event_meeting
        FOREIGN KEY (meeting_id) REFERENCES meetings (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_meeting_calendar_event_status_next_attempt
    ON meeting_calendar_event (status, next_attempt_at);

CREATE INDEX IF NOT EXISTS idx_meeting_calendar_event_user
    ON meeting_calendar_event (slack_user_id);
