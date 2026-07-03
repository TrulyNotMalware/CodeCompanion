-- -----------------------------------------------------------------------------
-- AI agent: per-turn usage/audit history
-- -----------------------------------------------------------------------------
-- Rationale:
--   The Pod shares one Anthropic identity, so token spend is a shared budget.
--   Every agent turn appends one row with its outcome, error code, token usage
--   (from the sidecar's terminal `done` event), and wall-clock duration, making
--   per-user / per-channel consumption auditable.
--
-- Behaviour:
--   - Append-only; rows are never updated.
--   - Indexed by created_at (time-range reports) and session_key (per-thread).
--
-- Apply BEFORE rolling out the agent usage-recording code in any environment
-- with `ddl-auto: none` (prod). dev/local with auto-ddl pick this up
-- automatically.
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS agent_turn_history (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_key     VARCHAR(160) NOT NULL,
    requester_id    VARCHAR(255) NOT NULL,
    channel         VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(36)  NOT NULL,
    outcome         VARCHAR(16)  NOT NULL,
    error_code      VARCHAR(64)  NULL,
    input_tokens    BIGINT       NULL,
    output_tokens   BIGINT       NULL,
    duration_ms     BIGINT       NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    INDEX idx_agent_turn_history_created_at (created_at),
    INDEX idx_agent_turn_history_session_key (session_key)
);
