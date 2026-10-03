<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-10-03 -->

# infrastructure/src/test/kotlin/dev/notypie/repository/cve

## Purpose
Specs for the CVE persistence lane in main `repository/cve/`. Four `Jpa*RepositoryTest` `@DataJpaTest` specs
run the JPQL and native claim-token CAS on the shared H2; five `*RepositoryImplTest` MockK specs pin the
mapping / delegation layer and are the only coverage of the MariaDB-only `INSERT IGNORE` claims (H2 cannot
execute them).

## Key Files
| File | Description |
|------|-------------|
| `JpaCveEventRepositoryTest.kt` | `@DataJpaTest`. Two `claimForSummary` calls on one row → `1` then `0`, winner's token and app-clock `updatedAt` on the row; `markDone` with a foreign token → `0`, owning token → `1` and token cleared; `markFailed` bumps `retryCount` and sets `nextAttemptAt`; `releaseClaim` returns to PENDING with the budget intact; `findClaimable` over PENDING / retry-elapsed FAILED / dead-letter / future-backoff / DONE / SUMMARIZING rows (scoped to the block's `externalId`s); `resetStuck` resets only the stale SUMMARIZING row; status counters asserted as deltas over a baseline; `countEventsByTopic` omits empty topics; `findRecentDoneEvents` newest-first with display name and limit; `resetDeadLetter` / `resetDeadLetters` at a ceiling of 50; a stale candidate cannot claim a row that became a dead-letter `markFailed`, `releaseClaim` and `resetStuck` stamp `updated_at` with the bound `now` (not the DB clock). `resetStuck` turns the stale claim `FAILED` with `retryCount` 1, the given `nextAttemptAt` and no token; a stale claim on its last retry is dead-lettered and never claimable again; a reset row is not claimable in the same tick, only once the backoff passes. |
| `JpaCveTopicRepositoryTest.kt` | `@DataJpaTest`. `findAllOrderByTopicKey` includes inactive rows in key order (filtered by prefix); `countActive` as a delta; `setActive` on a known key → `1`, unknown → `0`. `CveTopicRepositoryImpl.upsert` inside a `TransactionTemplate` that already read the topic → `IllegalStateException` ("outside a transaction") and the row keeps its name. Replica race: a row committed in another transaction while a delegating repository reports the first locked read empty → the `REQUIRES_NEW` insert loses the unique key, the second locked read syncs the display name while `active` stays `false` (two locked reads asserted). |
| `JpaCveDeliveryRepositoryTest.kt` | `@DataJpaTest` injecting all four JPA repos plus a `DataSource` (raw `JdbcTemplate` to age `created_at`, since `@CreationTimestamp` ignores supplied values). `findUndelivered`: only DONE events; only the topic's `deliveryMode`; inactive topics excluded; already-delivered pairs excluded; fan-out to every subscriber ordered by user id; horizon (`created_at >= since`) and cutoff (`updated_at < doneBefore`) boundaries; page limit; `dbNow()` within 60 s of the JVM clock. `afterSpec` deletes every table it wrote Two DIGEST events × two subscribers: `findUndelivered` interleaves users, `findUndeliveredByUser` keeps each user's pairs contiguous in event order, `findUndeliveredForUser` returns only that user's pairs. |
| `JpaCveCollectLedgerRepositoryTest.kt` | `@DataJpaTest`. Empty ledger → `findLatestWindowStart()` null; newest window across topics; `deleteOlderThan` prunes only older rows. `afterSpec` clears its rows |
| `CveEventRepositoryImplTest.kt` | MockK `JpaCveEventRepository`. `insertIgnore` delegates and truncates `title` to `TITLE_MAX_LENGTH` (512) and `rawContent` to `RAW_CONTENT_MAX_LENGTH` (60,000) via `slot` captures; `findClaimable` pages `PageRequest.of(0, limit)` and maps to `CveEvent`; `claimForSummary`, `markDone`, `markFailed`, `releaseClaim`, `resetStuck` pass through; empty `topicIds` short-circuits without a DB call; duplicated ids are de-duplicated `markFailed`, `releaseClaim` and `resetStuck` forward `now` unchanged. `markDone` drops a four-byte emoji straddling `AI_SUMMARY_MAX_BYTES` whole, cuts 30,000 Hangul characters to 21,845 (three bytes each), and keeps a summary exactly at the limit. |
| `CveTopicRepositoryImplTest.kt` | MockK `JpaCveTopicRepository`. `upsert`: a new key saves every field; an identical row → `false`, no save; differing non-active fields sync but `active` keeps the DB value; a row differing **only** in `active` is a no-op (yaml never reactivates); `findAllTopics` preserves order and the active flag; `countActive` / `setActive` delegate; `findActiveTopics` maps field by field Built with a stub `PlatformTransactionManager`; a lost insert (`saveAndFlush` throws `DataIntegrityViolationException`) syncs the row from the second `findLockedByTopicKey` keeping its `active`, and a violation with no row behind it is rethrown. Snapshot isolation: the impl behind a real `TransactionInterceptor` proxy over `SnapshotIsolationTransactionManager` (a plain read opens a read view; a locked read or save after it throws the translated 1020) — the lost-insert race and a rename of an existing row both complete (fail when `upsert` reads before locking in one transaction). |
| `CveSubscriptionRepositoryImplTest.kt` | MockK subscription + topic repos. `subscribe` calls `insertIgnore` once per distinct topic and sums only real inserts; empty lists skip the DB for both `subscribe` and `unsubscribe`; `findSubscribedTopics` orders by `topicKey` and never touches the topic table when there are no subscriptions |
| `CveDeliveryRepositoryImplTest.kt` | MockK `JpaCveDeliveryRepository`. `claim` maps `1` / `0` to `true` / `false`; `findUndelivered`, `findUndeliveredByUser` and `findUndeliveredForUser` wrap `limit` into `PageRequest.of(0, limit)` and forward the bounds; `dbNow` delegates |
| `CveCollectLedgerRepositoryImplTest.kt` | MockK `JpaCveCollectLedgerRepository`. `claimWindow` `1` / `0` → `true` / `false`; `latestWindowStart` and `deleteOlderThan` forward unchanged |

## For AI Agents

### Working In This Directory
- **`CveTopicRepositoryImpl.upsert` is called from `when` scopes.** A `then` leaf runs inside the test
  transaction, where `upsert` refuses to run.
- **`@DataJpaTest` here does not roll back.** Kotest container scopes run outside the test transaction, so
  every `saveAndFlush` commits to the one H2 shared by all `@DataJpaTest` specs. The rules that follow:
  unique `externalId` / `topicKey` / `userId` per block, assertions scoped by those keys or as deltas
  against a baseline, and `afterSpec { deleteAll() }` wherever a block leaves claimable or joinable rows
  behind (`JpaCveDeliveryRepositoryTest` and `JpaCveCollectLedgerRepositoryTest` do; `JpaCveEventRepositoryTest`
  intentionally does not and filters instead).
- **Retry ceilings are chosen so leaked rows cannot interfere**: the dead-letter block uses `maxRetries = 50`
  because rows from other blocks stay far below it. Keep that discipline for new counter / revive cases.
- **`INSERT IGNORE` is never executed in this module.** `insertIgnore`, `claim`, `claimWindow` are only
  delegation-tested; a change to their SQL needs a run against MariaDB, not a new spec here.
- `JpaCveSubscriptionRepository` has no H2 spec; `findByUserId` / `deleteByUserIdAndTopicIdIn` are derived
  queries, so the gap is small but real.
- Time in the H2 specs is a fixed `LocalDateTime.of(2026, 7, 13, 12, 0)` passed as `now`; only
  `JpaCveDeliveryRepositoryTest` uses the wall clock because `created_at` is DB-stamped.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.cve.*'
./gradlew :infrastructure:test --tests 'dev.notypie.repository.cve.JpaCveEventRepositoryTest'
```
`@DataJpaTest` specs boot `TestApplication` and share one cached context / H2 with the meeting specs. Any
new WHERE guard needs both the winning and the losing path asserted.

### Common Patterns
- `@DataJpaTest @ApplyExtension(extensions = [SpringExtension::class])` with `@Autowired constructor(...)`.
- Rows from `testFixtures/.../schema/CveTopicCreator.kt` and `CveEventCreator.kt` (`createCveTopicSchema`,
  `createCveEventSchema`, `createCveSubscriptionSchema`, `createCveDeliverySchema`,
  `createCveTopicDefinition`, `createUndeliveredCveEvent`).
- `*ImplTest`: one fresh `mockk` per `given`, `verify(exactly = 1)` with named arguments, `slot()` for
  truncation checks.

## Dependencies

### Internal
- `infrastructure/src/main/kotlin/dev/notypie/repository/cve/` (+ `schema/`)
- `infrastructure/src/testFixtures/kotlin/dev/notypie/schema/`
- `infrastructure/src/test/kotlin/dev/notypie/TestApplication.kt`

### External
Kotest + `kotest-extensions-spring`, MockK, `spring-boot-starter-data-jpa-test`, H2, Spring `JdbcTemplate`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
