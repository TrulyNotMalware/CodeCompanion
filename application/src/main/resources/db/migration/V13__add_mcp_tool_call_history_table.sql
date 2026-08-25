-- -----------------------------------------------------------------------------
-- MCP domain tools: per-call audit history
-- -----------------------------------------------------------------------------
-- Rationale:
--   The agent lane's model can invoke domain tools over MCP. Every call appends
--   one row: which tool, on whose behalf (from the per-turn scoped token), the
--   role that was resolved at call time, and how dispatch ended. turn_id joins
--   agent_turn_history.idempotency_key, linking tool calls to their agent turn.
--
-- Behaviour:
--   - Append-only; rows are never updated.
--   - Unauthenticated probes are rejected before dispatch and leave no row.
--   - Indexed by created_at (time-range reports) and requester_id (per-user).
--
-- Apply BEFORE rolling out the MCP tool code in any environment with
-- `ddl-auto: none` (prod). dev/local with auto-ddl pick this up automatically.
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS mcp_tool_call_history (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    tool_name     VARCHAR(64)   NOT NULL,
    requester_id  VARCHAR(255)  NOT NULL,
    session_key   VARCHAR(160)  NOT NULL,
    turn_id       VARCHAR(36)   NOT NULL,
    resolved_role VARCHAR(16)   NOT NULL,
    outcome       VARCHAR(16)   NOT NULL,
    error_code    VARCHAR(64)   NULL,
    arguments_json VARCHAR(2000) NULL,
    duration_ms   BIGINT        NOT NULL,
    created_at    DATETIME(6)   NOT NULL,
    INDEX idx_mcp_tool_call_history_created_at (created_at),
    INDEX idx_mcp_tool_call_history_requester_id (requester_id)
);
