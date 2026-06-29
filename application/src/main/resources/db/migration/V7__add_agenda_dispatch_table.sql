-- -----------------------------------------------------------------------------
-- Phase 4 #15: Daily Agenda DM — once-per-day dispatch claim ledger
-- -----------------------------------------------------------------------------
-- Rationale:
--   Each morning, after the configured send time, the agenda scheduler DMs every
--   user the list of meetings they are attending today. This table is the
--   idempotency ledger that guarantees the agenda for a given calendar day is sent
--   at most once, even across overlapping scheduler ticks and restarts.
--
-- Behaviour:
--   - agenda_date is the PRIMARY KEY, so claiming a day is an atomic
--     `INSERT IGNORE INTO agenda_dispatch (agenda_date, created_at) ...`. A row
--     inserted (1 affected row) means *this* tick owns today's agenda; 0 affected
--     rows means a concurrent tick already claimed it and this tick stands down.
--   - No FK: the ledger is keyed purely on the local calendar date.
--
-- Apply BEFORE rolling out the agenda application code in any environment with
-- `ddl-auto: none` (prod). dev/local with auto-ddl pick this up automatically.
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS agenda_dispatch (
    agenda_date DATE     NOT NULL PRIMARY KEY,
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
