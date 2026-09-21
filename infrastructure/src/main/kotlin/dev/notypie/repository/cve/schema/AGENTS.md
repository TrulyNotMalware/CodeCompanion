<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# infrastructure/repository/cve/schema

## Purpose
JPA entities and enums for the CVE lane. Every unique constraint here is load-bearing: it is what turns an
`INSERT IGNORE` in the parent package into an atomic claim.

## Key Files
| File | Description |
|------|-------------|
| `CveTopicSchema.kt` | `enum CveTopicCategory { LANGUAGE, FRAMEWORK, CVE, ETC }`, `enum CveSourceType { GITHUB_RELEASE, NVD_CVE, RSS }`, `enum CveDeliveryMode { IMMEDIATE, DIGEST }`; `@Entity(name = "cve_topic")`, `uk_cve_topic_topic_key`. Columns: `topic_key` (64, `val`), `display_name` (128), `category` (16), `source_type` (32), `source_config?` (`TEXT`), `delivery_mode` (16), `active`, `created_at`, `updated_at?` — every non-key business column is `var` for `upsert` |
| `CveEventSchema.kt` | `enum CveSummaryStatus { PENDING, SUMMARIZING, DONE, FAILED }`; `@Entity(name = "cve_event")`, `uk_cve_event_topic_external` on `(topic_id, external_id)`, indexes `idx_cve_event_summary_status`, `idx_cve_event_status_created_at`, `idx_cve_event_topic_status_id`, `idx_cve_event_status_next_attempt`. Columns: `topic_id`, `external_id` (255), `title` (512), `raw_content` (`TEXT`), `ai_summary?` (`TEXT`, `var`), `summary_status` (16, `var`), `claim_token?` (36, `var`), `retry_count` (`var`), `next_attempt_at?` (`var`), `published_at?`, `created_at`, `updated_at?` |
| `CveSubscriptionSchema.kt` | `@Entity(name = "cve_subscription")`, `uk_cve_subscription_user_topic` on `(user_id, topic_id)`, index on `topic_id`. Columns: `user_id` (64), `topic_id`, `created_at` |
| `CveDeliverySchema.kt` | `enum CveDeliveryStatus { SENT, FAILED }`; `@Entity(name = "cve_delivery")`, `uk_cve_delivery_event_user` on `(event_id, user_id)`, index on `user_id`. Columns: `event_id`, `user_id` (64), `status` (16, `var`), `created_at` |
| `CveCollectLedgerSchema.kt` | `@Entity(name = "cve_collect_ledger")`, `uk_cve_collect_ledger_topic_window` on `(topic_id, window_start)`. Columns: `topic_id`, `window_start`, `created_at`. No mutable state, no claim token |

## For AI Agents

### Working In This Directory
- **No JPA relations.** `topic_id`, `event_id` and `user_id` are plain columns; the parent package joins
  them with unrelated-entity `JOIN ... ON` in JPQL. Adding a `@ManyToOne` would change the fetch behaviour
  of every query in the lane — do not.
- **Enum columns are `@Enumerated(STRING)` with explicit `length`** (16 or 32). A constant longer than the
  length fails at insert on MariaDB; never rename a constant that has rows.
- **`cve_event.title` is 512 and `raw_content` is `TEXT`;** `CveEventRepositoryImpl` truncates to 512 /
  60,000 before the native insert. Change the column and the constant together.
- **Each `cve_event` index keys one hot query** (the in-file comments say which: the dispatcher's DONE scan
  by `created_at`, `/latest`'s per-topic DONE read, `findClaimable`'s due-check). A new query shape usually
  needs a new index and a `V*` migration.
- `CveTopicSchema` is the only entity whose business columns are `var`, because `upsert` mutates and re-saves
  the managed instance. `active` is `var` too but is only written by `setActive`.
- Tables: `V14__add_cve_bot_tables.sql` (`cve_topic`, `cve_event`, `cve_subscription`, `cve_delivery`),
  `V15__add_cve_collect_ledger_table.sql`; indexes in `V16` / `V17`.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.cve.*'
```
No entity-only spec; the `@DataJpaTest` specs under `src/test/kotlin/dev/notypie/repository/cve/` persist
these entities on H2 through the builders in `src/testFixtures/kotlin/dev/notypie/schema/{CveTopicCreator,
CveEventCreator}.kt`. H2 does not reproduce MariaDB-only behaviour (`INSERT IGNORE`, `TEXT` limits), so
verify constraint behaviour against a real database.

### Common Patterns
- `@Entity(name = "<table>")` doubles as the table name; `@Table` carries only constraints and indexes.
- `id: Long = 0` IDENTITY, `createdAt = LocalDateTime.now()` default plus `@CreationTimestamp`,
  `@UpdateTimestamp` only where rows mutate.
- Enums declared top-level in the same file as the entity that stores them.

## Dependencies

### Internal
None.

### External
Jakarta Persistence, Hibernate `@CreationTimestamp` / `@UpdateTimestamp`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
