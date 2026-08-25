-- -----------------------------------------------------------------------------
-- Meeting participants: add absent_reason_detail for free-text "Other" reasons
-- -----------------------------------------------------------------------------
-- Rationale:
--   When a participant declines with RejectReason.OTHER the decline modal now
--   collects a free-text explanation. It is stored alongside the enum reason so
--   the host sees it on `/meetup list`.
--
-- Behaviour:
--   - Adds `absent_reason_detail VARCHAR(255) NULL`; existing rows stay NULL.
--   - Only populated for OTHER declines; every other reason leaves it NULL.
--
-- Apply this script BEFORE rolling out application code that reads or persists
-- `absent_reason_detail`. For dev/local with auto-ddl enabled, Hibernate applies
-- it automatically. In prod, execute manually — schema auto-migration is disabled.
-- -----------------------------------------------------------------------------

ALTER TABLE meeting_participants ADD COLUMN IF NOT EXISTS absent_reason_detail VARCHAR(255) NULL;
