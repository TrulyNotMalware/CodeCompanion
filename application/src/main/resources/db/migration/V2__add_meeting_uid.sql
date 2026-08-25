-- -----------------------------------------------------------------------------
-- Meetings: add meeting_uid column for Slack-facing business identifier
-- -----------------------------------------------------------------------------
-- Rationale:
--   The `meetings.id` BIGINT IDENTITY column is an internal persistence PK
--   and is unsuitable for external exposure (guessable, leaks volume).
--   `meeting_uid` (UUID v4, generated in the domain entity at construction)
--   is the public identifier rendered in Slack messages and used as the
--   payload for interaction buttons in future stages (e.g. cancel a meeting
--   by UID).
--
-- Behaviour:
--   - Adds `meeting_uid CHAR(36)` with a UNIQUE index.
--   - Existing rows receive a freshly generated UUID so the NOT NULL + UNIQUE
--     constraints can be applied without violating integrity. After backfill
--     the column is locked to NOT NULL.
--
-- Apply this script BEFORE rolling out application code that reads or
-- persists `meeting_uid`. For environments with auto-ddl enabled (dev/local)
-- Hibernate applies it automatically. In prod, execute manually — schema
-- auto-migration is disabled there.
-- -----------------------------------------------------------------------------

-- Add nullable first so existing rows don't fail NOT NULL constraint during backfill.
ALTER TABLE meetings ADD COLUMN IF NOT EXISTS meeting_uid CHAR(36) NULL;

-- Backfill any existing rows with freshly generated UUIDs.
UPDATE meetings SET meeting_uid = UUID() WHERE meeting_uid IS NULL;

-- Lock down to NOT NULL now that all rows have values.
ALTER TABLE meetings MODIFY COLUMN meeting_uid CHAR(36) NOT NULL;

-- Enforce uniqueness; meeting_uid is the externally exposed identifier.
CREATE UNIQUE INDEX IF NOT EXISTS idx_meetings_meeting_uid ON meetings (meeting_uid);
