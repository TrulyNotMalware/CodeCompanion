-- -----------------------------------------------------------------------------
-- Google Calendar: index google_oauth_state by Slack user
-- -----------------------------------------------------------------------------
-- Rationale:
--   `/calendar disconnect` deletes every OAuth state of the requester
--   (DELETE FROM google_oauth_state WHERE slack_user_id = ?) inside the slash
--   command's transaction, whether or not a connection exists. V24 indexed only
--   expires_at, so that DELETE scans the primary key, and InnoDB keeps a lock on
--   every record a locking scan reads until the transaction ends (documented
--   InnoDB behaviour, not measured on this schema): other users' connects (the
--   state INSERT) and callbacks (the consume UPDATE) wait for the slash
--   transaction to commit. With this index the DELETE locks the requester's
--   index entries and the gap next to them (REPEATABLE READ next-key and gap
--   locks; a user with no state still locks the gap where that id would sort),
--   so a connect whose state sorts into that gap can still wait, but the other
--   rows are no longer locked.
--
-- Behaviour:
--   - No data change; the JPA mapping declares the same index name, so
--     dev/local with auto-ddl pick this up automatically.
--
-- Apply after V24 (it needs the table) in any environment with
-- `ddl-auto: none` (prod), at any time: nothing refers to the index by name, so
-- its order relative to the deploy does not matter (the runbook applies it
-- together with V24 and V25). Only an index is added, so the script is safe
-- while the previous release still runs.
-- Re-runnable: CREATE INDEX IF NOT EXISTS leaves an existing index in place.
-- Rollback: DROP INDEX IF EXISTS idx_google_oauth_state_slack_user_id ON google_oauth_state;
--
-- Size: do not assume the table is small. Expired states are deleted only
-- when some callback succeeds (deleteExpired runs after a successful consume)
-- and a user's states on their disconnect, so abandoned connects accumulate
-- between successful callbacks. Check SELECT COUNT(*) FROM google_oauth_state
-- before applying.
--
-- Online DDL (the procedure the V19-V23 headers describe, executed here rather
-- than left in a comment): a CREATE INDEX that waits for its metadata lock
-- behind a long transaction makes every later connect and callback wait too,
-- and MariaDB's default lock_wait_timeout is a day. The statements below use a
-- 5 s timeout and the in-place, non-locking form, which the server rejects at
-- once instead of falling back to a table copy. Re-run the file if it times
-- out. The SET lasts for the rest of the client session; reconnect or run
-- SET SESSION lock_wait_timeout = DEFAULT afterwards when the session is reused.
-- -----------------------------------------------------------------------------

SET SESSION lock_wait_timeout = 5;

CREATE INDEX IF NOT EXISTS idx_google_oauth_state_slack_user_id
    ON google_oauth_state (slack_user_id) ALGORITHM=INPLACE LOCK=NONE;
