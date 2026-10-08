-- -----------------------------------------------------------------------------
-- Google Calendar (per-user OAuth): connection store + one-time OAuth state
-- -----------------------------------------------------------------------------
-- Rationale:
--   `/meetup calendar connect` links a Slack user to their own Google Calendar.
--   The bot keeps one refresh token per Slack user so the mirror worker can write
--   meetings into that user's primary calendar without asking again. The token is
--   stored encrypted (AES-256-GCM, key = slack.app.calendar.google.token-encryption-key);
--   the column holds "v1.<iv>.<ciphertext>" and is opaque to SQL.
--
--   google_oauth_state is the CSRF/replay guard for the OAuth redirect: the bot
--   issues a random state bound to the Slack user, and the callback claims it with
--   one atomic UPDATE (consumed_at IS NULL AND expires_at > now). A replayed or
--   expired callback loses that UPDATE and gets "link no longer valid".
--
-- Behaviour:
--   - One connection row per Slack user (uk_google_calendar_connection_user);
--     reconnecting rewrites the token in place, disconnecting deletes the row.
--   - google_subject is the id_token `sub` (stable account id) and google_email
--     its `email`, both display/comparison only: a reconnect revokes the replaced
--     grant at Google only when the account is known to differ, because Google's
--     revoke endpoint revokes the whole grant for that account and client, which
--     would also kill the token just stored.
--   - status REVOKED is written by the mirror worker when Google rejects the
--     refresh token (invalid_grant); `/meetup calendar status` then asks the user
--     to reconnect.
--   - Expired state rows are purged opportunistically on the next connect.
--
-- Apply BEFORE rolling out the calendar application code in any environment with
-- `ddl-auto: none` (prod). dev/local with auto-ddl pick this up automatically.
-- Both tables are independent of every existing table, so the script is safe to
-- apply while the previous release still runs; rollback is DROP TABLE on both.
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS google_calendar_connection (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    slack_user_id     VARCHAR(64)  NOT NULL,
    google_subject    VARCHAR(255) NULL,
    google_email      VARCHAR(255) NULL,
    refresh_token_enc TEXT         NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    connected_at      DATETIME(6)  NOT NULL,
    revoked_at        DATETIME(6)  NULL,
    last_error        TEXT         NULL,
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NULL,
    CONSTRAINT uk_google_calendar_connection_user UNIQUE (slack_user_id)
);

CREATE TABLE IF NOT EXISTS google_oauth_state (
    state         VARCHAR(64) NOT NULL PRIMARY KEY,
    slack_user_id VARCHAR(64) NOT NULL,
    expires_at    DATETIME(6) NOT NULL,
    consumed_at   DATETIME(6) NULL,
    created_at    DATETIME(6) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_google_oauth_state_expires_at
    ON google_oauth_state (expires_at);
