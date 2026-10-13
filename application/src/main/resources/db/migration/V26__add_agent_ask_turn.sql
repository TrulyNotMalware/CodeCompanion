-- -----------------------------------------------------------------------------
-- AI agent: /ask turn ledger
-- -----------------------------------------------------------------------------
-- Rationale:
--   `/ask` posts the question to the channel through the outbox and answers in
--   that message's thread. The answer turn can only start once Slack returned
--   the message ts, and until now that hand-off lived in memory: a crash, a
--   restart with turns queued or running, or a failed answer transaction left
--   the question unanswered and nothing recovered it. This table records the
--   delivered question before the outbox row is marked SUCCESS, so a turn that
--   has not reached an outcome is found again by the resume sweep.
--
-- Behaviour:
--   - One row per delivered question. event_id is the outbox_message.event_id
--     of the question row (not its idempotency_key, which answer rows share and
--     which is not unique per ask); message_ts is the Slack ts, i.e. the thread
--     the answer goes to. Prompt, requester and channel are not copied: the
--     turn re-reads them from the outbox row, which the outbox retention keeps
--     far longer than the sweep's give-up window.
--   - status: PENDING (waiting for a run), IN_PROGRESS (a run owns it),
--     COMPLETED (an answer, a failure notice or an overload notice was staged),
--     ABANDONED (the sweep gave up once run_count reached its budget or
--     created_at passed its give-up window and staged the failure notice, or
--     the outbox row is gone).
--   - attempt_count is the ownership token. Only the PENDING -> IN_PROGRESS
--     transition raises it, it never goes down, and every later transition is
--     one UPDATE guarded by the value its owner saw, so a run that lost the row
--     writes nothing.
--   - run_count is the give-up budget, kept apart from the token like
--     outbox_message.send_count (V22): the same transition raises it, and the
--     give-back of a turn the sidecar reported busy takes that one back, so a
--     busy sidecar never spends the budget. Only runs that were cut off (a
--     crash, a restart, a failed outcome transaction) stay counted.
--   - created_at and updated_at are bound from the application clock on every
--     insert and transition and carry no database default or ON UPDATE clause,
--     like V24/V25: the sweep compares updated_at with that same clock.
--   - No foreign key to outbox_message: the outbox retention purge deletes the
--     question row long after its turn ended, and the ledger row stays.
--
-- Apply BEFORE rolling out the application code that writes it in any
-- environment with `ddl-auto: none` (prod). dev/local with auto-ddl pick this up
-- automatically. Nothing in the previous release reads or writes this table, so
-- the script is safe to apply while it still runs; rollback is DROP TABLE.
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS agent_ask_turn (
    event_id      VARCHAR(36) NOT NULL PRIMARY KEY,
    message_ts    VARCHAR(32) NOT NULL,
    status        VARCHAR(16) NOT NULL,
    attempt_count INT         NOT NULL DEFAULT 0,
    run_count     INT         NOT NULL DEFAULT 0,
    created_at    DATETIME(6) NOT NULL,
    updated_at    DATETIME(6) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_agent_ask_turn_status_updated_at
    ON agent_ask_turn (status, updated_at);
