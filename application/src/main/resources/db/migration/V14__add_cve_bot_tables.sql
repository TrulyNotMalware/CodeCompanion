-- -----------------------------------------------------------------------------
-- CVE-Bot: topic subscription notifications (schema for M1)
-- -----------------------------------------------------------------------------
-- Rationale:
--   Users subscribe to admin-managed topics (languages, frameworks, CVE feeds).
--   Collectors ingest source events per topic, an AI worker summarizes each
--   event exactly once, and subscribers receive the stored summary as a DM via
--   the existing outbox. Topics are supplied from yaml at boot (upsert by
--   topic_key); user identity is a plain Slack user id string, matching the
--   rest of the schema (no FK user table).
--
-- Behaviour:
--   - cve_topic: upserted at boot from config; rows absent from config are
--     left untouched. delivery_mode picks immediate DM vs daily digest.
--   - cve_subscription: one row per (user, topic); unique key enforces it.
--   - cve_event: unique(topic_id, external_id) makes ingestion idempotent.
--     summary_status/claim_token drive the summarize-once worker CAS.
--   - cve_delivery: unique(event_id, user_id) prevents duplicate sends —
--     the outbox itself does not deduplicate.
--
-- Apply BEFORE rolling out the CVE-bot code in any environment with
-- `ddl-auto: none` (prod). dev/local with auto-ddl pick this up automatically.
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS cve_topic (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    topic_key     VARCHAR(64)   NOT NULL,
    display_name  VARCHAR(128)  NOT NULL,
    category      VARCHAR(16)   NOT NULL,
    source_type   VARCHAR(32)   NOT NULL,
    source_config TEXT          NULL,
    delivery_mode VARCHAR(16)   NOT NULL,
    active        BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at    DATETIME(6)   NOT NULL,
    updated_at    DATETIME(6)   NULL,
    CONSTRAINT uk_cve_topic_topic_key UNIQUE (topic_key)
);

CREATE TABLE IF NOT EXISTS cve_subscription (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    VARCHAR(64)  NOT NULL,
    topic_id   BIGINT       NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    CONSTRAINT uk_cve_subscription_user_topic UNIQUE (user_id, topic_id),
    INDEX idx_cve_subscription_topic_id (topic_id)
);

CREATE TABLE IF NOT EXISTS cve_event (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    topic_id        BIGINT        NOT NULL,
    external_id     VARCHAR(255)  NOT NULL,
    title           VARCHAR(512)  NOT NULL,
    raw_content     TEXT          NOT NULL,
    ai_summary      TEXT          NULL,
    summary_status  VARCHAR(16)   NOT NULL,
    claim_token     VARCHAR(36)   NULL,
    retry_count     INT           NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6)   NULL,
    published_at    DATETIME(6)   NULL,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NULL,
    CONSTRAINT uk_cve_event_topic_external UNIQUE (topic_id, external_id),
    INDEX idx_cve_event_summary_status (summary_status)
);

CREATE TABLE IF NOT EXISTS cve_delivery (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id   BIGINT       NOT NULL,
    user_id    VARCHAR(64)  NOT NULL,
    status     VARCHAR(16)  NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    CONSTRAINT uk_cve_delivery_event_user UNIQUE (event_id, user_id),
    INDEX idx_cve_delivery_user_id (user_id)
);
