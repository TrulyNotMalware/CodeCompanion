-- -----------------------------------------------------------------------------
-- Standup: record the member's Skip on the prompt DM
-- -----------------------------------------------------------------------------
-- Rationale:
--   The standup prompt DM has two buttons, "Fill in standup" and "Skip". Both
--   carried the same routing, and the context behind them never looked at which
--   one was clicked, so Skip opened the answer modal, recorded nothing, left the
--   prompt in place, got the member nudged before the cutoff and showed them as
--   "(no response)" in the summary. Skip is now a recorded state of the member's
--   dispatch row (docs/wiki/decisions.md #52).
--
-- Behaviour:
--   - standup_session_dispatch.skipped_at DATETIME NULL; existing rows stay NULL.
--   - Stamped once, on the member's first Skip while the session is COLLECTING
--     and before its cutoff (UPDATE ... WHERE skipped_at IS NULL); a repeated
--     click keeps the first time. dm_status is not touched: it still describes
--     the DM, and the FAILED "skipped: ..." reason of a dispatch the scheduler
--     retired is a different thing.
--   - The nudge leaves out members with skipped_at set, and the summary shows
--     them as "(skipped)" unless they answered afterwards; an answer always wins.
--
-- Apply BEFORE rolling out the release that maps the column in any environment
-- with `ddl-auto: none` (prod): every read of a dispatch row (the DM sender's
-- pending query, the nudge, the summary, `/standup answer`) selects
-- `skipped_at`, so without the column the new binary fails them all. A nullable
-- column is invisible to the previous release, so the script is safe while that
-- release still runs. dev/local with auto-ddl pick this up automatically.
-- Re-runnable: ADD COLUMN IF NOT EXISTS leaves an existing column in place
-- (note 1060 instead of an error).
-- Rollback: the previous release ignores the column (its Skip opens the answer
-- modal again), so it can stay. To remove it, run
-- ALTER TABLE standup_session_dispatch DROP COLUMN IF EXISTS skipped_at;
-- only once no Pod of this release is left.
--
-- Online DDL (as in V28): the statement uses a 5 s metadata-lock timeout and
-- the in-place, non-locking form, which the server rejects at once instead of
-- falling back to a table copy. Re-run the file if it times out. The SET lasts
-- for the rest of the client session; reconnect or run
-- SET SESSION lock_wait_timeout = DEFAULT afterwards when the session is reused.
-- -----------------------------------------------------------------------------

SET SESSION lock_wait_timeout = 5;

ALTER TABLE standup_session_dispatch
    ADD COLUMN IF NOT EXISTS skipped_at DATETIME NULL, ALGORITHM=INPLACE, LOCK=NONE;
