<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-09-21 -->

# db/migration

## Purpose
Ordered MariaDB patch scripts, one per schema change, in versioned `V<n>__*.sql` naming. No migration
tool is a dependency of any module, so nothing runs these automatically: `local`/`dev`/`slack-live` shape tables from the JPA mappings via
`ddl-auto: update`, and `prod` (`ddl-auto: none`) has each script applied by hand before the matching code
rolls out. The folder is therefore the production schema runbook and the audit trail of every change.

## Key Files
| File | Description |
|------|-------------|
| `V1__outbox_pk_event_id.sql` | `outbox_message` PK moves from `idempotency_key` to `event_id` (backfilled); `idempotency_key` stays as an indexed non-PK column |
| `V2__add_meeting_uid.sql` | `meetings.meeting_uid CHAR(36)` unique public identifier, backfilled with `UUID()` then locked `NOT NULL` |
| `V3__add_meeting_end_at.sql` | `meetings.end_at DATETIME NULL`; NULL means "start + 1h" and is never backfilled |
| `V4__add_standup_tables.sql` | `standup_routine`, `standup_routine_member`, `standup_session`, `standup_session_dispatch` (with `claim_token`), `standup_answer` plus their unique keys and indexes |
| `V5__add_meeting_reminder_table.sql` | `meeting_reminder` idempotency ledger, unique `(meeting_id, offset_minutes)`, FK cascade to `meetings` |
| `V6__add_standup_session_nudged_at.sql` | `standup_session.nudged_at` once-only claim column (plain `ADD COLUMN`, not idempotent) |
| `V7__add_agenda_dispatch_table.sql` | `agenda_dispatch` keyed by `agenda_date`; claimed with `INSERT IGNORE` |
| `V8__add_participant_absent_reason_detail.sql` | `meeting_participants.absent_reason_detail VARCHAR(255) NULL` |
| `V9__add_agent_session_table.sql` | `agent_session` mapping a Slack conversation key to the sidecar session id, unique `session_key` |
| `V10__add_agent_turn_history_table.sql` | Append-only `agent_turn_history` (outcome, tokens, duration), indexed by `created_at` and `session_key` |
| `V11__outbox_transport_neutral_envelope.sql` | Outbox V2: drops `metadata`, `command_detail_type`, `type`; adds `transport` (default `SLACK`); `payload` becomes opaque `TEXT`; `schema_version` default 2. Destructive, not idempotent |
| `V12__add_user_command_role_table.sql` | `user_command_role` with an `ENUM('USER','AI_USER','DEVELOPER','ADMIN')` column, unique `user_id` |
| `V13__add_mcp_tool_call_history_table.sql` | Append-only `mcp_tool_call_history`; `turn_id` joins `agent_turn_history.idempotency_key` |
| `V14__add_cve_bot_tables.sql` | `cve_topic`, `cve_subscription`, `cve_event`, `cve_delivery` with the unique keys that make ingestion and delivery idempotent |
| `V15__add_cve_collect_ledger_table.sql` | `cve_collect_ledger`, unique `(topic_id, window_start)` multi-instance collector gate |
| `V16__add_cve_event_status_created_at_index.sql` | `idx_cve_event_status_created_at` on `cve_event (summary_status, created_at)` |
| `V17__add_cve_event_query_indexes.sql` | `idx_cve_event_topic_status_id` and `idx_cve_event_status_next_attempt` on `cve_event` |

## For AI Agents

### Working In This Directory
- **Next free number is `V18`.** Never renumber, reorder or edit a script that has shipped; add a new one.
- **Every script is MariaDB dialect.** `ADD COLUMN IF NOT EXISTS`, `CREATE INDEX IF NOT EXISTS`,
  `DROP INDEX IF EXISTS … ON`, inline `INDEX` clauses inside `CREATE TABLE`, `ENUM`, `DATETIME(6)`,
  `ON UPDATE CURRENT_TIMESTAMP` and `INSERT IGNORE` semantics are all assumed. They will not run on H2 (the
  test profile) or unmodified MySQL, and no test ever executes them.
- **A new entity needs both halves:** a JPA schema class under `infrastructure/repository/<area>/schema/`
  and a script here whose column names, types and constraint names match it. `ddl-auto: update` covers local
  runs, so a missing script only surfaces in prod as a startup failure or a runtime SQL error.
- **ALTER-only scripts presume the Hibernate-created base table.** `V1`, `V2`, `V3`, `V6`, `V8` and `V11`
  patch tables `ddl-auto: update` already created; on a fresh local schema Hibernate has usually applied the
  end state already, so running them by hand is a no-op at best and an error at worst (`V11` drops columns a
  fresh schema never had).
- **Idempotency is uneven.** Most scripts guard with `IF NOT EXISTS`; `V6` (`ADD COLUMN`) and `V11`
  (`DROP COLUMN`, `MODIFY`) fail on a second run. Prefer the guarded forms in new scripts, and say so in the
  header when a step cannot be guarded.
- **Ordering assumptions live in the headers.** `V1` expects an `outbox_message` with an `idempotency_key`
  PK; `V11` expects the outbox to be drained first; `V17` supersedes the status-only index from `V14` for
  the `/latest` query. Read the "Apply … BEFORE rolling out" line before sequencing a deploy.
- Keep the header block (Rationale / Behaviour / Apply-before-rollout) — it is the only place the reason
  for a change is recorded, since prod applies these outside any migration tool.
- The profile YAML carries no `spring.flyway.*` keys (the inert `enabled: false` leftovers were removed
  2026-09-21). Do not add Flyway config — adopting the tool would also require a baseline for every
  environment that already applied `V1`–`V17` by hand.
- Files here are packaged into the boot jar by `processResources` although the app never reads them.

### Testing Requirements
- Apply a new script against a real MariaDB (the `local` orbstack instance or a `slack-live`-profile database)
  with the `mariadb` client and compare `SHOW CREATE TABLE` with the JPA mapping; a `ddl-auto: validate`
  boot against that database is the closest thing to an automated check.
- Run the script twice when it is meant to be re-runnable; `IF NOT EXISTS` guards are the contract.
- Infrastructure tests use H2 with schema generated from the mappings, so a green test suite says nothing
  about these files.

### Common Patterns
- Naming: `V<n>__<snake_case_description>.sql`, one concern per script.
- Claim/idempotency ledgers follow one shape: a `UNIQUE` key on the natural pair, a nullable `claim_token`
  or timestamp column for the CAS, and `created_at DATETIME(6) NOT NULL` (`V4`, `V5`, `V6`, `V7`, `V14`,
  `V15`).
- Index changes use `DROP INDEX IF EXISTS … ON <table>; CREATE INDEX …` so re-application is safe (`V16`,
  `V17`).
- Append-only audit tables (`V10`, `V13`) index `created_at` plus one lookup key and never get `updated_at`.

## Dependencies

### Internal
- `infrastructure/repository/outbox/schema/` — `V1`, `V11`
- `infrastructure/repository/meeting/schema/` — `V2`, `V3`, `V5`, `V7`, `V8`
- `infrastructure/repository/standup/schema/` — `V4`, `V6`
- `infrastructure/repository/agent/schema/` — `V9`, `V10`
- `infrastructure/repository/authorization/schema/` — `V12`
- `infrastructure/repository/mcp/schema/` — `V13`
- `infrastructure/repository/cve/schema/` — `V14`–`V17`
- `../../application-*.yaml` — `ddl-auto` per profile decides whether a script is applied by hand

### External
MariaDB (`mariadb` CLI for manual application). No migration tool.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
