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
-- -----------------------------------------------------------------------------

DROP INDEX IF EXISTS idx_outbox_status_created_at ON outbox_message;
CREATE INDEX idx_outbox_status_created_at ON outbox_message (status, created_at);

DROP INDEX IF EXISTS idx_outbox_status_updated_at ON outbox_message;
CREATE INDEX idx_outbox_status_updated_at ON outbox_message (status, updated_at);
