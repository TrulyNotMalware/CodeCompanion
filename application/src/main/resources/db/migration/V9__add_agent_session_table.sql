-- -----------------------------------------------------------------------------
-- AI agent: conversation-session mapping table
-- -----------------------------------------------------------------------------
-- Rationale:
--   `@bot ask` mentions run one AI turn against the claude-sidecar. The sidecar
--   only resumes conversational context when the previous turn's session id is
--   echoed back, so each Slack conversation ("<channel>:<thread_ts>") keeps its
--   latest provider session id here.
--
-- Behaviour:
--   - The unique key on `session_key` enforces "one backend session per
--     conversation"; concurrent turns for the same key are already rejected
--     upstream by the sidecar's per-sessionKey gate.
--
-- Apply BEFORE rolling out the agent application code in any environment with
-- `ddl-auto: none` (prod). dev/local with auto-ddl pick this up automatically.
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS agent_session (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_key         VARCHAR(160) NOT NULL,
    provider_session_id VARCHAR(255) NOT NULL,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NULL,
    CONSTRAINT uk_agent_session_session_key UNIQUE (session_key)
);
