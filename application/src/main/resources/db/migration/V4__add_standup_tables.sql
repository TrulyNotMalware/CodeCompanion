-- -----------------------------------------------------------------------------
-- Phase 3: Standup Bot — domain tables + scheduling claim token
-- -----------------------------------------------------------------------------
-- Rationale:
--   Phase 3 #10 introduced the standup-bot domain (Routine, StandupSession,
--   SessionDispatch, StandupAnswer). Phase 3 #12 added the `claim_token` column
--   on `standup_session_dispatch` so the scheduler's atomic CAS only acknowledges
--   *its own* claim's outcome (prevents stuck-recovery + re-claim races where one
--   tick would silently clobber another).
--
-- Behaviour:
--   - Creates all five tables fresh (no pre-existing data to migrate).
--   - Unique keys enforce "channel × day = 1 session" and "session × user = 1
--     dispatch / 1 answer" at the DB level — both invariants the scheduler
--     relies on for idempotency on restart.
--
-- Apply BEFORE rolling out the standup application code in any environment with
-- `ddl-auto: none` (prod). dev/local with auto-ddl pick this up automatically.
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS standup_routine (
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    routine_uid            VARCHAR(36)  NOT NULL UNIQUE,
    name                   VARCHAR(60)  NOT NULL,
    creator_id             VARCHAR(255) NOT NULL,
    command_channel        VARCHAR(255) NOT NULL,
    summary_channel        VARCHAR(255) NOT NULL,
    questions              TEXT         NOT NULL,
    trigger_local_time     TIME         NOT NULL,
    cutoff_offset_seconds  BIGINT       NOT NULL,
    weekdays               VARCHAR(80)  NOT NULL,
    routine_timezone       VARCHAR(64)  NOT NULL,
    is_active              BOOLEAN      NOT NULL,
    created_at             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             DATETIME     NULL ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS standup_routine_member (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    routine_id    BIGINT       NOT NULL,
    user_id       VARCHAR(255) NOT NULL,
    user_timezone VARCHAR(64)  NOT NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_standup_routine_member_routine
        FOREIGN KEY (routine_id) REFERENCES standup_routine (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_standup_routine_member_routine
    ON standup_routine_member (routine_id);

CREATE TABLE IF NOT EXISTS standup_session (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_uid         VARCHAR(36) NOT NULL UNIQUE,
    routine_uid         VARCHAR(36) NOT NULL,
    session_date        DATE        NOT NULL,
    cutoff_at           DATETIME    NOT NULL,
    status              VARCHAR(16) NOT NULL,
    summary_message_ts  VARCHAR(64) NULL,
    created_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME    NULL ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_standup_session_routine_date UNIQUE (routine_uid, session_date)
);

CREATE INDEX IF NOT EXISTS idx_standup_session_cutoff
    ON standup_session (cutoff_at);

CREATE TABLE IF NOT EXISTS standup_session_dispatch (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id      BIGINT       NOT NULL,
    user_id         VARCHAR(255) NOT NULL,
    dm_trigger_at   DATETIME     NOT NULL,
    dm_sent_at      DATETIME     NULL,
    dm_status       VARCHAR(16)  NOT NULL,
    failure_reason  TEXT         NULL,
    claim_token     VARCHAR(36)  NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NULL ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_standup_dispatch_session_user UNIQUE (session_id, user_id),
    CONSTRAINT fk_standup_dispatch_session
        FOREIGN KEY (session_id) REFERENCES standup_session (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_standup_dispatch_trigger_status
    ON standup_session_dispatch (dm_status, dm_trigger_at);

CREATE TABLE IF NOT EXISTS standup_answer (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id    BIGINT       NOT NULL,
    user_id       VARCHAR(255) NOT NULL,
    responses     TEXT         NOT NULL,
    submitted_at  DATETIME     NOT NULL,
    CONSTRAINT uk_standup_answer_session_user UNIQUE (session_id, user_id),
    CONSTRAINT fk_standup_answer_session
        FOREIGN KEY (session_id) REFERENCES standup_session (id) ON DELETE CASCADE
);
