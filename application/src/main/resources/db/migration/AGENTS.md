<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-19 -->

# db/migration

## Purpose
Ordered MariaDB patch scripts, one per schema change, in versioned `V<n>__*.sql` naming. No migration
tool is a dependency of any module, so nothing runs these automatically: `local`/`dev`/`slack-live` shape tables from the JPA mappings via
`ddl-auto: update`, and `prod` (`ddl-auto: none`) has each script applied by hand before the matching code
rolls out. The folder is therefore the production schema runbook and the audit trail of every change.
The number is the order a script was written in, not always the order it is applied in: a data fix that must wait
for the new code (V21) carries a lower number than schema changes that must precede it (V22, V23). The headers and
the release checklist below decide the order.

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
| `V10__add_agent_turn_history_table.sql` | Append-only `agent_turn_history` (outcome, tokens, duration), indexed by `created_at` and `session_key`. "Append-only" in the header means rows are never updated: since 2026-10-15 the retention purge deletes rows past `agent.retention.history-days` through `idx_agent_turn_history_created_at` (no new index; the shipped script is unchanged) |
| `V11__outbox_transport_neutral_envelope.sql` | Outbox V2: drops `metadata`, `command_detail_type`, `type`; adds `transport` (default `SLACK`); `payload` becomes opaque `TEXT`; `schema_version` default 2. Destructive, not idempotent |
| `V12__add_user_command_role_table.sql` | `user_command_role` with an `ENUM('USER','AI_USER','DEVELOPER','ADMIN')` column, unique `user_id` |
| `V13__add_mcp_tool_call_history_table.sql` | Append-only `mcp_tool_call_history`; `turn_id` joins `agent_turn_history.idempotency_key`. Purged like `V10`'s table, with the same retention, through `idx_mcp_tool_call_history_created_at` (2026-10-15) |
| `V14__add_cve_bot_tables.sql` | `cve_topic`, `cve_subscription`, `cve_event`, `cve_delivery` with the unique keys that make ingestion and delivery idempotent |
| `V15__add_cve_collect_ledger_table.sql` | `cve_collect_ledger`, unique `(topic_id, window_start)` multi-instance collector gate |
| `V16__add_cve_event_status_created_at_index.sql` | `idx_cve_event_status_created_at` on `cve_event (summary_status, created_at)` |
| `V17__add_cve_event_query_indexes.sql` | `idx_cve_event_topic_status_id` and `idx_cve_event_status_next_attempt` on `cve_event` |
| `V18__add_meeting_version_and_indexes.sql` | `meetings.version` (optimistic lock), unique `(meeting_id, user_id)` on `meeting_participants` (check for duplicates first — see the script header), indexes on `meetings`, `meeting_participants`, `standup_routine` |
| `V19__add_outbox_status_indexes.sql` | `idx_outbox_status_created_at` and `idx_outbox_status_updated_at` on `outbox_message` — the poller, CDC claim, retention purge and health scalars all filter on `status`. `CREATE INDEX IF NOT EXISTS`, so a re-run never drops the index the running poller uses; the header carries the online-DDL procedure shared with V20/V22/V23 (short `lock_wait_timeout`, `ALGORITHM=INPLACE LOCK=NONE`) |
| `V20__add_outbox_attempt_count.sql` | `outbox_message.attempt_count INT NOT NULL DEFAULT 0` — incremented by every claim/reclaim; the ownership token for the relay's lease renewal, rate-limit deferral and terminal status write. Its header carries the rollout constraint: this release must not run beside a pre-V20 release (stop the old pods first), and the online-DDL procedure (short `lock_wait_timeout`, `ALGORITHM=INPLACE, LOCK=NONE`) |
| `V21__fix_inverted_meeting_end_at.sql` | Data fix, no DDL: `end_at = NULL` (V3's "start + 1h" marker) where `end_at <= start_at`, rows left by reschedules that moved only `start_at`, with `version = version + 1` so a meeting write that read the row earlier fails its optimistic-lock check instead of restoring the inverted value. Idempotent; the header carries the inspection query and the rollout constraint (after V18, once every replica writes through the `@Version` entity) |
| `V22__add_outbox_send_count.sql` | `outbox_message.send_count INT NOT NULL DEFAULT 0` — raised by `renewClaim` right before a send, taken back by the rate-limit deferral; the recovery sweep's abandon budget (`outbox.polling.max-sends`) and the health probe's retrying-row counter. Ships with V20 under the same rollout constraint and online-DDL procedure |
| `V23__widen_outbox_payload_to_mediumtext.sql` | `outbox_message.payload` `TEXT` → `MEDIUMTEXT NOT NULL`: under strict `sql_mode` a payload over 65,535 bytes failed its write (a long AI answer or a full standup summary was never staged). Re-runnable. The header carries the first-run cost: the retention purge ships in the same release, so the first run meets the whole history — measure the table, try `ALGORITHM=INPLACE, LOCK=NONE` with a short `lock_wait_timeout` (rejected at once if impossible), and run the copying form only at a quiet moment or after the purge. Safe for old binaries and rollbacks |
| `V24__add_google_calendar_tables.sql` | `google_calendar_connection` (one row per Slack user, `google_subject`/`google_email` from the id_token, `refresh_token_enc` = `TokenCipher` output, `status` ACTIVE/REVOKED, unique `slack_user_id`) and `google_oauth_state` (`state` PK, `slack_user_id`, `expires_at`, `consumed_at`; index on `expires_at`) for `/calendar connect`. Independent of every existing table; `CREATE TABLE IF NOT EXISTS`, safe beside the previous release; rollback is `DROP TABLE` on both |
| `V25__add_meeting_calendar_event.sql` | `meeting_calendar_event`, the Google Calendar mirror queue: one row per `(meeting_id, slack_user_id)` (unique `uk_meeting_calendar_event_meeting_user`, FK cascade to `meetings`), `google_event_id VARCHAR(1024)`, `status` PENDING/SYNCING/SYNCED/FAILED, `change_seq BIGINT DEFAULT 1`, `attempts`, `next_attempt_at DATETIME(6)`, `claim_token`, `last_error`, `created_at DATETIME(6) NOT NULL` and `updated_at DATETIME(6) NULL` without a database default or `ON UPDATE` (as in `V24`; every insert and transition binds them); indexes `(status, next_attempt_at)` and `(slack_user_id)`. The row is a dirty marker (the worker recomputes the desired event from the meeting) and `change_seq` is the generation the completion CAS compares, so a change during the Google call is synced again without losing the event id. Apply after `V24`; new table only, safe beside the previous release; rollback is `DROP TABLE` |
| `V26__add_agent_ask_turn.sql` | `agent_ask_turn`, the `/ask` turn ledger: one row per delivered question, `event_id VARCHAR(36)` PK = the `outbox_message.event_id` of the question row (not its `idempotency_key`, which answer rows share and which is not unique per ask), `message_ts VARCHAR(32)` (the Slack ts, i.e. the answer thread), `status VARCHAR(16)` PENDING/IN_PROGRESS/COMPLETED/ABANDONED, `attempt_count INT DEFAULT 0` (the ownership token: only the PENDING → IN_PROGRESS transition raises it, it never goes down) and `run_count INT DEFAULT 0` (the give-up budget: the same transition raises it, the give-back of a turn the sidecar reported busy takes it back), `created_at` / `updated_at DATETIME(6) NOT NULL` without a database default or `ON UPDATE` (every insert and transition binds them from the application clock); index `(status, updated_at)` for the resume sweep. No foreign key to `outbox_message` (the retention purge deletes the question row long after the turn ended). New table only, `CREATE TABLE IF NOT EXISTS`, safe beside the previous release; rollback is `DROP TABLE`. Apply before the release that writes it. Since 2026-10-15 finished rows (`COMPLETED`/`ABANDONED`) past `agent.retention.ask-turn-days` are purged through the same `(status, updated_at)` index, two status ranges as in the outbox purge (no new index) |
| `V27__add_google_oauth_state_user_index.sql` | `idx_google_oauth_state_slack_user_id` on `google_oauth_state (slack_user_id)`: `/calendar disconnect` deletes the requester's states by `slack_user_id` inside the slash transaction, and with only `V24`'s `expires_at` index that `DELETE` scanned the primary key, locking every state row until commit; with the index it locks the requester's index entries and the gap next to them, so a connect whose state sorts into that gap can still wait (InnoDB REPEATABLE READ locking, not measured on this schema). The file executes the online-DDL form itself (`SET SESSION lock_wait_timeout = 5;` then `CREATE INDEX IF NOT EXISTS … ALGORITHM=INPLACE LOCK=NONE`; MariaDB accepts `IF NOT EXISTS` together with the algorithm and lock clauses, and an existing index gives note 1061 instead of an error), so it is re-runnable and never queues behind a long transaction or falls back to a table copy. The `SET` outlives the statement in that client session. Do not size it as a small table: expired states are deleted only when some callback succeeds (and a user's on disconnect), so abandoned connects accumulate; count the rows first. Apply after `V24`, at any time relative to the deploy; index only, safe beside the previous release; rollback is `DROP INDEX IF EXISTS … ON google_oauth_state`. **Not yet run on MariaDB** (2026-10-15: no instance was available to the author). Before the first rollout, on a disposable schema at `V24` (and `V25`) with rows in `google_oauth_state`: run `V27` once (this run builds the index through `ALGORITHM=INPLACE LOCK=NONE`, so it is the one that proves the online form works), run it again (expect note 1061 and no change), and compare `SHOW CREATE TABLE google_oauth_state` with `GoogleOAuthStateSchema`. Record the version and date here as for `V26` |
| `V28__add_google_calendar_connection_version.sql` | `google_calendar_connection.version BIGINT NOT NULL DEFAULT 0`, the optimistic-lock column of `GoogleCalendarConnectionSchema` (`@Version`, 2026-10-15, `docs/wiki/decisions.md` #47): `saveActive` reads the user's row and rewrites it, so of two callbacks of one user that read the same row the later commit used to overwrite the grant the earlier one had just stored without reporting it as replaced (that grant was never revoked); only MariaDB's snapshot isolation (1020) stopped it. Now the stale writer fails its version check on any database and the callback's one-shot store retry sees the grant it replaces; a `/calendar disconnect` that read the row before a reconnect committed fails the same way instead of deleting the new grant's row. The sync worker's `markRevoked` (a bulk JPQL `UPDATE`) does not bump the column. The file executes the online form (`SET SESSION lock_wait_timeout = 5;` then `ALTER TABLE … ADD COLUMN IF NOT EXISTS … , ALGORITHM=INPLACE, LOCK=NONE`; an existing column gives note 1060), so it is re-runnable. **Apply before the release that maps the column** (unlike `V27`, it cannot follow the deploy: every read of the connection row selects `version`, so connect, the callback's store, `/calendar status|disconnect`, the sync worker's token lookup and the revoke worker's re-read fail without it); after `V24`, safe beside the previous release, which ignores the column. Rollback: the column can stay; `ALTER TABLE google_calendar_connection DROP COLUMN IF EXISTS version` only once no Pod of this release is left. **Not yet run on MariaDB** (2026-10-15, same procedure as `V27`: run it twice on a disposable schema at `V24`, compare `SHOW CREATE TABLE google_calendar_connection` with the schema class, record version and date here) |

## For AI Agents

### Working In This Directory
- **Next free number is `V29`.** Never renumber, reorder or edit a script that has shipped; add a new one.
- **Number ≠ apply order: the V18–V23 release checklist.** `main` had stopped at `V17`, and #26 (`f9971700`,
  2026-10-06) shipped `V18`–`V23` together. An environment still at `V17` applies them in this order, which
  `../../k8s/README.md` ("One-time") and `docs/wiki/dev-environment.md` repeat:
  1. `V18`, after its header's duplicate check on `meeting_participants (meeting_id, user_id)` (delete the extra
     rows, keep the lowest `id`), or the unique key fails;
  2. `V19` and `V23`. `V23` follows its header: measure the table and try the online form first; if that is
     rejected and the copy would block outbox writes too long, `V23` may wait until after the deploy and the
     retention purge — the release only needs it for payloads over 65,535 bytes;
  3. `V20`, then `V22`. Steps 1–3 only add defaulted columns, indexes and a wider type that the pre-V20 binary
     never depends on, so they go in while the old release still serves;
  4. stop the old Pods, then deploy — the `Recreate` strategy in `../../k8s/deployment.yaml` does both in one
     rollout. This is how the
     "stop every old pod … then start the new release" constraint in the `V20` header is met; applying
     `V20`/`V22` before the old Pods stop does not break it;
  5. `V21`, only once every Pod runs the new release (an older binary's reschedule moves `start_at` alone and
     skips the `version` check).
  Readiness does not check the schema, so a skipped `V18` (every `meetings` query fails) or `V20`/`V22` (every
  outbox claim fails) passes the deploy gate. A future release that again needs a script *after* the rollout
  gets the next free number like any other and says so in its header; never renumber to make the order match.
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
  environment that applies these scripts by hand, each at its own last applied number. `V24` and `V25` ship
  together with the Google Calendar feature and, as of 2026-10-08, have not been applied by hand anywhere. Apply
  `V24`, then `V25`, before the deploy that ships them and before `GOOGLE_CALENDAR_ENABLED` turns `true`: the mirror
  hooks use both tables inside every meeting write (`application/src/main/resources/k8s/README.md`, "Google
  Calendar Mirror"). `V27` (2026-10-15) only indexes `google_oauth_state` and joins that sequence (`V24` → `V25` →
  `V27`); where `V24` is already live it can go in on its own at any time. Without it nothing fails: the disconnect
  `DELETE` only serialises with other users' connects and callbacks. `V28` (2026-10-15) joins the same sequence
  (`V24` → `V25` → `V27` → `V28`) but, where `V24` is already live, must still go in **before** the deploy that ships
  it: the entity maps its column. The same release stores refresh tokens as `v2` ciphertext bound to the row's
  `slack_user_id` (`impl/calendar/TokenCipher`, #46); a binary from before it reads only `v1`, so a rollback past this
  release (including the deploy workflow's `rollout undo`) turns every token stored or reconnected under it into
  `TOKEN_UNREADABLE` at that user's next sync (connection `REVOKED`, one reconnect DM). `V28` itself is harmless to
  that older binary.
- **`V26` ships with the `/ask` turn ledger and is new on 2026-10-13, so assume no hand-applied environment has
  it yet.** Apply it before the deploy that ships the ledger code. Where it is missing the application still starts
  (`ddl-auto: none` does not check the schema) and `/ask` still answers, because the delivery listener falls back
  to a turn without a ledger row; but every question then logs an ERROR, no turn survives a restart, and the 60 s
  resume sweep logs its own ERROR each tick (`application/service/agent/AGENTS.md`; inferred from the code and
  from the spec case whose ledger insert always fails, not run against a schema without the table). The script
  was applied twice to an empty database on MariaDB 12.3.3 (2026-10-13, local instance; the second run changed
  nothing) and the ledger's native statements were run on that table through the `mariadb` client
  (`infrastructure/repository/agent/AGENTS.md`). The specs still run on tables Hibernate generates on H2, and the
  application has not run against the script's table. Compare `SHOW CREATE TABLE` with the mapping when applying it (`status` is
  `@Enumerated(STRING)` with length 16, which Hibernate generated as an `enum` column on H2, while the script
  creates `VARCHAR(16)` — the same mapping style as `V25`'s `status`).
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
- Claim/idempotency ledgers follow one shape: a `UNIQUE` key on the natural pair and a nullable `claim_token`
  or timestamp column for the CAS. Their `created_at` comes in two forms: `DATETIME NOT NULL DEFAULT
  CURRENT_TIMESTAMP` (`V4`, `V5`, `V7`; `V4` and `V5` add `updated_at DATETIME NULL ON UPDATE CURRENT_TIMESTAMP`)
  and `DATETIME(6) NOT NULL` with no default, bound by the application (`V14`, `V15`, `V24`, `V25`, `V26`). New
  tables use the second form. `V26` is the exception to the first sentence: its key is the outbox `event_id`
  itself and its CAS token is `attempt_count`, like the outbox row's.
- New index scripts use `CREATE INDEX IF NOT EXISTS`, so a re-run never drops an index in use, and execute the online form (`SET SESSION lock_wait_timeout = 5;` + `ALGORITHM=INPLACE LOCK=NONE`) as `V27` does; a new defaulted column uses `ADD COLUMN IF NOT EXISTS` with the same session timeout and `, ALGORITHM=INPLACE, LOCK=NONE` (`V28`). `V19` keeps that form only in its header comment (it has shipped, so it stays as it is): applied verbatim, its plain `CREATE INDEX` can wait up to a day for its metadata lock. Older ones use `DROP INDEX IF EXISTS … ON <table>; CREATE INDEX …` (`V16`,
  `V17`).
- Append-only audit tables (`V10`, `V13`) index `created_at` plus one lookup key and never get `updated_at`; the
  `created_at` index also serves the retention purge's `DELETE … WHERE created_at < ? LIMIT ?`.

## Dependencies

### Internal
- `infrastructure/repository/outbox/schema/` — `V1`, `V11`, `V19`, `V20`, `V22`
- `infrastructure/repository/meeting/schema/` — `V2`, `V3`, `V5`, `V7`, `V8`
- `infrastructure/repository/standup/schema/` — `V4`, `V6`
- `infrastructure/repository/agent/schema/` — `V9`, `V10`, `V26`
- `infrastructure/repository/authorization/schema/` — `V12`
- `infrastructure/repository/mcp/schema/` — `V13`
- `infrastructure/repository/cve/schema/` — `V14`–`V17`
- `infrastructure/repository/calendar/schema/` — `V24`, `V25`, `V27`, `V28`
- `../../application-*.yaml` — `ddl-auto` per profile decides whether a script is applied by hand

### External
MariaDB (`mariadb` CLI for manual application). No migration tool.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
