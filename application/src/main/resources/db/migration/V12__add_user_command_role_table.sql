-- -----------------------------------------------------------------------------
-- Command authorization: per-user role grants
-- -----------------------------------------------------------------------------
-- Rationale:
--   Mention commands are gated by role: USER (forms/help only), AI_USER (+ask),
--   DEVELOPER (+status/notice), ADMIN (everything). An actor without a row here
--   defaults to USER; `slack.app.authorization.bootstrap-admins` (config) names
--   admins that need no row, so the table can be bootstrapped.
--
-- Behaviour:
--   - Rows are managed directly in the DB for now; there are no in-bot grant
--     commands yet.
--
-- Apply BEFORE rolling out the authorization application code in any environment
-- with `ddl-auto: none` (prod). dev/local with auto-ddl pick this up automatically.
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS user_command_role (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    VARCHAR(64) NOT NULL,
    role       ENUM ('USER', 'AI_USER', 'DEVELOPER', 'ADMIN') NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NULL,
    CONSTRAINT uk_user_command_role_user_id UNIQUE (user_id)
);
