-- -----------------------------------------------------------------------------
-- Outbox PK migration: idempotency_key -> event_id
-- -----------------------------------------------------------------------------
-- Rationale:
--   Multi-recipient commands (e.g. ApprovalCallbackContext producing one
--   ApplyReject intent per participant) generate several events that share
--   the same idempotency_key of the originating command. Keeping
--   idempotency_key as the PK caused insert collisions and dropped messages.
--   event_id is unique per individual Slack message, which is the correct
--   grain for the outbox relay.
--
-- Behaviour:
--   - Old PK on idempotency_key is dropped.
--   - event_id becomes the new PK (populated from SlackEventPayload.eventId).
--   - idempotency_key is retained as a non-PK indexed column for dedupe /
--     troubleshooting lookups ("find every outbox message produced by command
--     X").
--
-- Apply this script BEFORE rolling out the application code that expects
-- event_id to be the PK. For environments with auto-ddl enabled (dev/local)
-- this migration is applied automatically by Hibernate. In prod, execute
-- manually — schema auto-migration is disabled.
-- -----------------------------------------------------------------------------

-- Add event_id column if not present, backfill from idempotency_key for existing rows.
ALTER TABLE outbox_message ADD COLUMN IF NOT EXISTS event_id VARCHAR(255) NULL;
UPDATE outbox_message SET event_id = idempotency_key WHERE event_id IS NULL;
ALTER TABLE outbox_message MODIFY COLUMN event_id VARCHAR(255) NOT NULL;

-- Swap primary key.
ALTER TABLE outbox_message DROP PRIMARY KEY;
ALTER TABLE outbox_message ADD PRIMARY KEY (event_id);

-- Ensure idempotency_key is still queryable.
CREATE INDEX IF NOT EXISTS idx_outbox_idempotency_key ON outbox_message (idempotency_key);
