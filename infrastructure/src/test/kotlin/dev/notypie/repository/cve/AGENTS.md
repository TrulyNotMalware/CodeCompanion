<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-10-01 -->

# infrastructure/src/test/kotlin/dev/notypie/repository/cve

## Purpose
Specs for the CVE persistence lane in main `repository/cve/`. Four `Jpa*RepositoryTest` `@DataJpaTest` specs
run the JPQL and native claim-token CAS on the shared H2; five `*RepositoryImplTest` MockK specs pin the
mapping / delegation layer and are the only coverage of the MariaDB-only `INSERT IGNORE` claims (H2 cannot
execute them).

## Key Files
| File | Description |
|------|-------------|
| `JpaCveEventRepositoryTest.kt` | `@DataJpaTest`. Two `claimForSummary` calls on one row → `1` then `0`, winner's token and app-clock `updatedAt` on the row; `markDone` with a foreign token → `0`, owning token → `1` and token cleared; `markFailed` bumps `retryCount`, sets `nextAttemptAt` and stamps `updatedAt` from the bound `now`; `releaseClaim` returns to PENDING with the budget intact and the bound `updatedAt`; `findClaimable` over PENDING / retry-elapsed FAILED / dead-letter / future-backoff / DONE / SUMMARIZING rows (scoped to the block's `externalId`s); `resetStuck` fails only the stale SUMMARIZING row (FAILED, `retryCount + 1`, token cleared, bound `now` stamp, `nextAttemptAt` = the bound backoff) and leaves the live row alone, and the reset row is out of `findClaimable(now)` until its backoff elapses (H6); a row stuck on its last retry (ceiling 20) lands in `countDeadLetter` and out of `findClaimable`; status counters asserted as deltas over a baseline; `countEventsByTopic` omits empty topics; `findRecentDoneEvents` newest-first with display name and limit; `resetDeadLetter` / `resetDeadLetters` at a ceiling of 50; a stale candidate cannot claim a row that became a dead-letter |
| `JpaCveTopicRepositoryTest.kt` | `@DataJpaTest`. `findAllOrderByTopicKey` includes inactive rows in key order (filtered by prefix); `countActive` as a delta; `setActive` on a known key → `1`, unknown → `0`; a `CveTopicRepositoryImpl` over a `JpaCveTopicRepository by repository` delegate whose first `findByTopicKey` returns null (the other replica's insert not yet visible) upserts an existing key → the real H2 unique violation is absorbed, one row remains and it carries the new display name (V10) |
| `JpaCveDeliveryRepositoryTest.kt` | `@DataJpaTest` injecting all four JPA repos plus a `DataSource` (raw `JdbcTemplate` to age `created_at`, since `@CreationTimestamp` ignores supplied values). `findUndelivered`: only DONE events; only the topic's `deliveryMode`; inactive topics excluded; already-delivered pairs excluded; fan-out to every subscriber ordered by user id; horizon (`created_at >= since`) and cutoff (`updated_at < doneBefore`) boundaries; page limit; two events × two users → `findUndelivered` interleaves users while `findUndeliveredByUser` keeps each user contiguous (T16); `findUndeliveredForUser` returns only that user's undelivered pairs in event order (a delivered pair and the other subscriber excluded) and honours the page limit (R4); `dbNow()` within 60 s of the JVM clock. `afterSpec` deletes every table it wrote |
| `JpaCveCollectLedgerRepositoryTest.kt` | `@DataJpaTest`. Empty ledger → `findLatestWindowStart()` null; newest window across topics; `deleteOlderThan` prunes only older rows. `afterSpec` clears its rows |
| `CveEventRepositoryImplTest.kt` | MockK `JpaCveEventRepository`. `insertIgnore` delegates and truncates `title` to `TITLE_MAX_LENGTH` (512) and `rawContent` to `RAW_CONTENT_MAX_LENGTH` (60,000) via `slot` captures; `findClaimable` pages `PageRequest.of(0, limit)` and maps to `CveEvent`; `claimForSummary`, `markDone`, `markFailed`, `releaseClaim`, `resetStuck` pass through (including the `now` stamp and `resetStuck`'s `nextAttemptAt`); `markDone` cuts a 65,536+-byte summary before a 4-byte emoji to 65,532 bytes and passes a summary of exactly 65,535 bytes unchanged; empty `topicIds` short-circuits without a DB call; duplicated ids are de-duplicated |
| `CveTopicRepositoryImplTest.kt` | MockK `JpaCveTopicRepository`. `upsert`: a new key saves every field; an identical row → `false`, no save; differing non-active fields sync but `active` keeps the DB value; a row differing **only** in `active` is a no-op (yaml never reactivates); an insert throwing `DataIntegrityViolationException` with the winner's row on re-read → that row is synced, `active` untouched; a violation with no row on re-read → rethrown; `findAllTopics` preserves order and the active flag; `countActive` / `setActive` delegate; `findActiveTopics` maps field by field |
| `CveSubscriptionRepositoryImplTest.kt` | MockK subscription + topic repos. `subscribe` calls `insertIgnore` once per distinct topic and sums only real inserts; empty lists skip the DB for both `subscribe` and `unsubscribe`; `findSubscribedTopics` orders by `topicKey` and never touches the topic table when there are no subscriptions |
| `CveDeliveryRepositoryImplTest.kt` | MockK `JpaCveDeliveryRepository`. `claim` maps `1` / `0` to `true` / `false`; `findUndelivered`, `findUndeliveredByUser` and `findUndeliveredForUser` wrap `limit` into `PageRequest.of(0, limit)` and forward `since` / `doneBefore` (and `userId`); `dbNow` delegates |
| `CveCollectLedgerRepositoryImplTest.kt` | MockK `JpaCveCollectLedgerRepository`. `claimWindow` `1` / `0` → `true` / `false`; `latestWindowStart` and `deleteOlderThan` forward unchanged |

## For AI Agents

### Working In This Directory
- **`@DataJpaTest` here does not roll back.** Kotest container scopes run outside the test transaction, so
  every `saveAndFlush` commits to the one H2 shared by all `@DataJpaTest` specs. The rules that follow:
  unique `externalId` / `topicKey` / `userId` per block, assertions scoped by those keys or as deltas
  against a baseline, and `afterSpec { deleteAll() }` wherever a block leaves claimable or joinable rows
  behind (`JpaCveDeliveryRepositoryTest` and `JpaCveCollectLedgerRepositoryTest` do; `JpaCveEventRepositoryTest`
  intentionally does not and filters instead).
- **Retry ceilings are chosen so leaked rows cannot interfere**: the dead-letter block uses `maxRetries = 50`
  because rows from other blocks stay far below it, and the stuck-on-last-retry block uses 20 so its
  exhausted row stays below the revive block's 50. Keep that discipline for new counter / revive cases.
- `findRecentDoneEvents`' "latest-topic" case assumes its topic id is not `1` (the default `topicId` of
  `createCveEventSchema`); run the whole `dev.notypie.repository.cve.*` package, not `*Event*` alone, or an
  empty `cve_topic` hands it id 1 and unrelated DONE rows leak in (pre-existing).
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
