-- -----------------------------------------------------------------------------
-- Outbox schema V2: transport-neutral codec envelope
-- -----------------------------------------------------------------------------
-- Rationale:
--   The relay now stores a codec-encoded, transport-neutral OutboundEnvelope in a
--   single opaque `payload` column and renders it to a wire payload at deliver
--   time. The pre-rendered Slack shape (payload JSON + metadata + type +
--   command_detail_type) is retired.
--
-- Behaviour:
--   - Drops the pre-render columns (metadata, type, command_detail_type).
--   - Reshapes `payload` from a JSON body Map to opaque TEXT holding the encoded
--     envelope string.
--   - Adds `transport` (defaults to SLACK — the only transport today).
--   - Bumps schema_version default to 2.
--
-- No data migration: the outbox is drained big-bang before this runs, so there
-- are no in-flight rows to carry forward. For auto-ddl environments (dev/local)
-- Hibernate applies the equivalent reshape automatically; in prod run this before
-- rolling out the V2 relay binary.
-- -----------------------------------------------------------------------------

ALTER TABLE outbox_message DROP COLUMN metadata;
ALTER TABLE outbox_message DROP COLUMN command_detail_type;
ALTER TABLE outbox_message DROP COLUMN type;

ALTER TABLE outbox_message ADD COLUMN transport VARCHAR(255) NOT NULL DEFAULT 'SLACK';
ALTER TABLE outbox_message MODIFY COLUMN payload TEXT NOT NULL;
ALTER TABLE outbox_message MODIFY COLUMN schema_version INT NOT NULL DEFAULT 2;
