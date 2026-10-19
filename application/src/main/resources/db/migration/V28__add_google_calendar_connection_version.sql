-- -----------------------------------------------------------------------------
-- Google Calendar: optimistic-lock version on google_calendar_connection
-- -----------------------------------------------------------------------------
-- Rationale:
--   saveActive (the OAuth callback's store) reads the user's connection row,
--   rewrites it and returns the replaced grant so the caller can revoke it at
--   Google. Two callbacks of one user that read the same row both rewrote it,
--   and the later commit silently won: its caller reported the row it had read,
--   not the grant the earlier commit had just stored, so that grant was never
--   revoked and stayed live at Google. MariaDB's snapshot isolation (1020)
--   happened to stop the second write; nothing else did. The entity now carries
--   @Version, so the stale writer fails its version check on every database,
--   the callback retries the whole store once, and the retry sees the grant
--   it replaces. A disconnect that read the row before a reconnect committed
--   fails the same way instead of deleting the new grant's row.
--
-- Behaviour:
--   - google_calendar_connection.version BIGINT NOT NULL DEFAULT 0; existing
--     rows start at 0. Hibernate increments it on every entity write; the
--     sync worker's markRevoked (a bulk UPDATE) leaves it alone on purpose.
--
-- Apply after V24 and BEFORE rolling out the release that maps the column in
-- any environment with `ddl-auto: none` (prod): every read of the connection
-- row (connect and its scope-denied check, the callback's store, `/calendar
-- status|disconnect`, the sync worker's token lookup, the revoke worker's
-- re-read) selects `version`, so without the column the new binary fails them
-- all. A defaulted column is invisible to the previous release, so the script
-- is safe while that release still runs. dev/local with auto-ddl pick this up
-- automatically.
-- Re-runnable: ADD COLUMN IF NOT EXISTS leaves an existing column in place
-- (note 1060 instead of an error).
-- Rollback: the previous release ignores the column, so it can stay. To remove
-- it, run ALTER TABLE google_calendar_connection DROP COLUMN IF EXISTS version;
-- only once no Pod of this release is left.
--
-- Online DDL (as in V27): a metadata lock queued behind a long transaction
-- makes every later connection read wait too, and MariaDB's default
-- lock_wait_timeout is a day. The statement below uses a 5 s timeout and the
-- in-place, non-locking form (MariaDB documents adding a trailing column with
-- a constant default as instant since 10.3; not run on this schema), which the
-- server rejects at once instead of falling back to a table copy. Re-run the
-- file if it times out. The SET lasts for the rest of the client session;
-- reconnect or run SET SESSION lock_wait_timeout = DEFAULT afterwards when the
-- session is reused.
-- -----------------------------------------------------------------------------

SET SESSION lock_wait_timeout = 5;

ALTER TABLE google_calendar_connection
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0, ALGORITHM=INPLACE, LOCK=NONE;
