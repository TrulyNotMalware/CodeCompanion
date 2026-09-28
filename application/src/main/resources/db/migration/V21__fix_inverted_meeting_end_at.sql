-- -----------------------------------------------------------------------------
-- Meetings: reset end_at on rows whose end is not after their start
-- -----------------------------------------------------------------------------
-- Rationale:
--   Before the reschedule path moved end_at together with start_at, a
--   reschedule only rewrote start_at. Moving a meeting later than its old end
--   left rows with end_at <= start_at. Such a row cannot be rehydrated into the
--   domain Meeting (its "end after start" invariant throws), and the old
--   duration arithmetic carried the negative duration forward on the next
--   reschedule.
--
-- Behaviour:
--   - Sets end_at to NULL on every row with end_at <= start_at. NULL is the
--     canonical "start + 1 hour" marker introduced by V3, so affected meetings
--     fall back to the default one-hour duration.
--   - Bumps version (V18) on the same rows. A cancel, reschedule or
--     add-participant that loaded the row before this UPDATE then fails its
--     optimistic-lock check and retries on the repaired row. Without the bump
--     its flush would succeed and write the old inverted end_at back.
--   - Rows with end_at NULL or end_at > start_at are untouched; re-running the
--     script is a no-op.
--   - Inspect the affected rows first:
--       SELECT id, meeting_uid, start_at, end_at FROM meetings
--       WHERE end_at IS NOT NULL AND end_at <= start_at;
--
-- Apply after V18, and only once every running replica writes meetings
-- through the @Version-checked managed entity. An older binary's bulk
-- reschedule does not check version and can recompute an end_at from a row it
-- read before this UPDATE; while such a replica is still serving, stop meeting
-- writes for the few seconds the script runs. It does not gate a rollout. The
-- application also drops an inverted end_at on the next reschedule. dev/local
-- with auto-ddl do not run it; execute manually in every environment that has
-- pre-existing meetings.
-- -----------------------------------------------------------------------------

UPDATE meetings
SET end_at = NULL, version = version + 1
WHERE end_at IS NOT NULL AND end_at <= start_at;
