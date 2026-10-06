-- -----------------------------------------------------------------------------
-- Outbox: status-keyed indexes for the poller, the CDC claim and the health probe
-- -----------------------------------------------------------------------------
-- Rationale:
--   Every hot query on outbox_message filters on status: findPendingMessages
--   (status = 'PENDING' ORDER BY created_at), findStuckInProgress and the
--   retention purge (status + updated_at), and the actuator health scalars. The
--   only index so far was idx_outbox_idempotency_key, so each of them was a full
--   scan plus filesort as the table grew — and until 2026-09-22 nothing ever
--   deleted a row.
--
-- Apply BEFORE rolling out in any environment with `ddl-auto: none` (prod).
-- dev/local with auto-ddl pick this up automatically.
--
-- Re-runnable: CREATE INDEX IF NOT EXISTS leaves an existing index in place, so
-- a second run never drops the index the running poller is using.
--
-- Online DDL (same procedure as V23): outbox_message is live while this runs,
-- and a CREATE INDEX that waits for its metadata lock behind a long transaction
-- (for example V23's size-measurement query in another session) makes every
-- later outbox write (each bot reply) wait too; MariaDB's default
-- lock_wait_timeout is a day. Run it with a short timeout and the in-place form,
-- which the server rejects at once instead of falling back to a table copy:
--     SET SESSION lock_wait_timeout = 5;
--     CREATE INDEX IF NOT EXISTS idx_outbox_status_created_at
--       ON outbox_message (status, created_at) ALGORITHM=INPLACE LOCK=NONE;
--     CREATE INDEX IF NOT EXISTS idx_outbox_status_updated_at
--       ON outbox_message (status, updated_at) ALGORITHM=INPLACE LOCK=NONE;
-- Re-run it if it times out.
-- -----------------------------------------------------------------------------

CREATE INDEX IF NOT EXISTS idx_outbox_status_created_at ON outbox_message (status, created_at);

CREATE INDEX IF NOT EXISTS idx_outbox_status_updated_at ON outbox_message (status, updated_at);
