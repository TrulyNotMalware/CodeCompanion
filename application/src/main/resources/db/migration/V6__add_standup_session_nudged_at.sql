-- -----------------------------------------------------------------------------
-- Phase 4: Standup non-responder nudge — once-only claim column
-- -----------------------------------------------------------------------------
-- Rationale:
--   Shortly before a session's cutoff the scheduler DMs members who received the
--   standup prompt but have not answered yet. `nudged_at` is the idempotency +
--   claim ledger that makes that exactly-once across ticks and restarts: the
--   scheduler's atomic CAS flips NULL → CURRENT_TIMESTAMP under
--   `status = 'COLLECTING'`, so only one tick ever wins the nudge for a session.
--
-- Behaviour:
--   - NULL while the session has not been nudged; stamped once the reminder fires.
--   - The selection query keys off (status = COLLECTING AND nudged_at IS NULL AND
--     cutoff_at within the nudge window), so a stamped row is never re-selected.
--
-- Apply BEFORE rolling out the nudge application code in any environment with
-- `ddl-auto: none` (prod). dev/local with auto-ddl pick this up automatically.
-- -----------------------------------------------------------------------------

ALTER TABLE standup_session ADD COLUMN nudged_at DATETIME NULL;
