<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# infrastructure/repository/cve

## Purpose
Persistence for the CVE / release-feed bot: admin topics, ingested source events with their AI-summary
lifecycle, per-user subscriptions, the per-(event, user) delivery ledger, and the once-per-window collect
ledger. Five port interfaces (`Cve*Repository`), five `open class Cve*RepositoryImpl` mapping layers, five
`JpaCve*Repository` Spring Data interfaces. Every multi-instance guarantee in this lane is a single SQL
statement: `INSERT IGNORE` on a unique key or a claim-token CAS.

## Key Files
| File | Description |
|------|-------------|
| `CveTopicRepository.kt` | `data class CveTopic(id, topicKey, displayName, category, sourceType, sourceConfig?, deliveryMode, active)`, `data class CveTopicDefinition(...)` (the yaml shape, `active = true` default); port `upsert(definition): Boolean`, `findActiveTopics()`, `findAllTopics()`, `findById(id)`, `countActive()`, `setActive(topicKey, active): Int` |
| `CveTopicRepositoryImpl.kt` | `upsert` inserts when `findByTopicKey` is null, otherwise syncs every field **except `active`** onto the managed row and returns `false` on a no-op match (`matches()` ignores `active` too) |
| `JpaCveTopicRepository.kt` | Derived `findByTopicKey`, `findByActiveTrueOrderByTopicKey`; JPQL `findAllOrderByTopicKey`, `countActive`, `@Modifying setActive`. Not `@Repository`-annotated (still registered by `@EnableJpaRepositories`) |
| `CveEventRepository.kt` | Records `CveEvent(id, topicId, externalId, title, rawContent, aiSummary?, summaryStatus, retryCount)`, `TopicEventCount(topicId, count)`, `CveRecentEvent(topicDisplayName, title, aiSummary?)`; port `insertIgnore(topicId, externalId, title, rawContent, publishedAt?): Int`, `findClaimable(now, maxRetries, limit)`, `claimForSummary(id, token, now, maxRetries): Int`, `markDone(id, token, summary, now)`, `markFailed(id, token, nextAttemptAt)`, `releaseClaim(id, token, nextAttemptAt)`, `resetStuck(olderThan)`, `countByStatus`, `countFailedRetryable(maxRetries)`, `countDeadLetter(maxRetries)`, `countEventsByTopic(topicIds)`, `findRecentDoneEvents(topicIds, limit)`, `resetDeadLetters(maxRetries)`, `resetDeadLetter(id, maxRetries)` |
| `CveEventRepositoryImpl.kt` | Truncates `title` to `TITLE_MAX_LENGTH = 512` and `rawContent` to `RAW_CONTENT_MAX_LENGTH = 60_000` before `insertIgnore`; `limit` → `PageRequest.of(0, limit)`; empty `topicIds` short-circuits to `emptyList()`, otherwise `distinct()` |
| `JpaCveEventRepository.kt` | Native `INSERT IGNORE` ingestion; JPQL `findClaimable` (PENDING / FAILED, `retryCount < :maxRetries`, backoff elapsed, id ASC); native CAS `claimForSummary` / `releaseClaim` / `markDone` / `markFailed` / `resetStuck`; JPQL counters, `countEventsByTopic` (constructor projection), `findRecentDoneEvents` (entity join to `cve_topic`), `resetDeadLetters` / `resetDeadLetter` (JPQL bulk update) |
| `CveSubscriptionRepository.kt` | Port `subscribe(userId, topicIds): Int`, `unsubscribe(userId, topicIds): Int`, `findSubscribedTopics(userId): List<CveTopic>` |
| `CveSubscriptionRepositoryImpl.kt` | `subscribe` loops `insertIgnore` per distinct topic and sums the inserted count; `unsubscribe` → `deleteByUserIdAndTopicIdIn`; `findSubscribedTopics` reads ids then `findAllById` on the topic repo, sorted by `topicKey` |
| `JpaCveSubscriptionRepository.kt` | Derived `findByUserId`, `deleteByUserIdAndTopicIdIn(): Long`; native `insertIgnore(userId, topicId): Int`. Not `@Repository`-annotated |
| `CveDeliveryRepository.kt` | `data class UndeliveredCveEvent(eventId, userId, topicKey, topicDisplayName, title, aiSummary?)`; port `claim(eventId, userId): Boolean`, `findUndelivered(deliveryMode, since, doneBefore, limit)`, `dbNow(): LocalDateTime` |
| `CveDeliveryRepositoryImpl.kt` | `claim` = affected rows `== 1`; `findUndelivered` wraps `limit` into a `PageRequest` |
| `JpaCveDeliveryRepository.kt` | Native `INSERT IGNORE ... 'SENT'` claim; JPQL `findUndelivered` joining `cve_topic` and `cve_subscription` to `cve_event` by unrelated-entity `ON`, anti-joining `cve_delivery` (`d.id IS NULL`), filtering `DONE`, `deliveryMode`, `active = true`, `createdAt >= :since`, `updatedAt < :doneBefore`, ordered by event id then user id; native `dbNow()` = `SELECT LOCALTIMESTAMP(6)` |
| `CveCollectLedgerRepository.kt` | Port `claimWindow(topicId, windowStart): Boolean`, `deleteOlderThan(cutoff): Int`, `latestWindowStart(): LocalDateTime?` |
| `CveCollectLedgerRepositoryImpl.kt` | Thin delegation; `claimWindow` = affected rows `== 1` |
| `JpaCveCollectLedgerRepository.kt` | Native `INSERT IGNORE` on `(topic_id, window_start)`; JPQL `deleteOlderThan`, `findLatestWindowStart` (`MAX(windowStart)`) |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `schema/` | `CveTopicSchema` (+ `CveTopicCategory`, `CveSourceType`, `CveDeliveryMode`), `CveEventSchema` (+ `CveSummaryStatus`), `CveSubscriptionSchema`, `CveDeliverySchema` (+ `CveDeliveryStatus`), `CveCollectLedgerSchema` (see `schema/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- **Four `INSERT IGNORE` claim signals; the affected-row count is the contract.** Event `insertIgnore`,
  `JpaCveSubscriptionRepository.insertIgnore`, `JpaCveDeliveryRepository.claim` and `claimWindow` return `1`
  when this call owns the row and `0` when a concurrent instance already did. `INSERT IGNORE` is MariaDB
  syntax and **cannot run on H2**, which is why each has a `*ImplTest` delegation spec instead of a
  `@DataJpaTest`.
- **The summary worker's safety is `claimForSummary` plus token-guarded `markDone` / `markFailed` /
  `releaseClaim`.** The claim re-checks `retry_count < :maxRetries` so a stale candidate cannot revive a
  dead-letter row; `releaseClaim` returns the row to PENDING **without** consuming the retry budget
  (sidecar backpressure is not a failure). Never replace a guarded UPDATE with load-modify-save.
- **Two clocks.** `claimForSummary` and `markDone` stamp `updated_at` from the app clock (`:now`) because
  `resetStuck` and the notification dispatcher's `doneBefore` cutoff compare against app-clock values;
  `created_at` is DB-stamped (`CURRENT_TIMESTAMP(6)`), so `findUndelivered`'s `since` horizon must be derived
  from `dbNow()`, never from `LocalDateTime.now()` — the DB (UTC) and JVM (KST) zones can differ by hours.
- **`upsert` never syncs `active` after the first insert.** Chat toggles (`setActive`) own that flag; a yaml
  reboot must not reactivate what an admin deactivated, and a row differing only in `active` is a no-op.
- **Native bulk updates bypass `@UpdateTimestamp`**, so every CAS sets `updated_at` explicitly. The
  dead-letter revives (`resetDeadLetters` / `resetDeadLetter`) deliberately do not — nothing reads
  `updated_at` on PENDING rows and the next claim re-stamps it.
- `findUndelivered` and `findRecentDoneEvents` join unrelated entities (`topicId` is a plain column, not a
  relation); Hibernate renders them as plain SQL joins, so they do run on H2.
- `CveDeliveryRepositoryImpl.claim` is `@Transactional` with default `REQUIRED`, so it joins the
  dispatcher's outbox-save transaction: a failed enqueue rolls the claim back and the pair is re-driven on
  the next tick.
- Beans: `JpaConfiguration.cveTopicRepository` / `cveEventRepository` / `cveSubscriptionRepository` /
  `cveCollectLedgerRepository` / `cveDeliveryRepository`. Consumers in `:application`: `CveTopicBootstrap`,
  `CveCollector`, `CveSummaryWorker`, `CveNotificationDispatcher`, `CveSubscriptionService`,
  `CveSubscriptionSlashServiceImpl`, `CveLatestQueryService`, `CveOpsService`, `OpsStatusService`.
  Migrations `V14`–`V17`.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.cve.*'
```
Pairing: `JpaCveEventRepositoryTest`, `JpaCveTopicRepositoryTest`, `JpaCveDeliveryRepositoryTest`,
`JpaCveCollectLedgerRepositoryTest` are `@DataJpaTest` on the shared H2 and exercise the JPQL / native CAS
(claim race, foreign token, stale dead-letter claim, `resetStuck`, `findUndelivered` filters);
`Cve*RepositoryImplTest` are MockK delegation specs and the **only** coverage of the `INSERT IGNORE` claim
mapping. `JpaCveSubscriptionRepository` has no H2 spec. A change to any WHERE guard needs a losing-path case
in the `Jpa*` spec, not only the winning one.

### Common Patterns
- Port interface + `open class *Impl` with `@Transactional` on every write + `Jpa*Repository`.
- Ports expose records (`CveTopic`, `CveEvent`, ...) built by a private `toRecord(schema)`; entities never
  leave the package.
- `limit: Int` on the port, `Pageable` on the JPA interface, `PageRequest.of(0, limit)` in the impl.
- Fully-qualified enum literals inside JPQL (`dev.notypie.repository.cve.schema.CveSummaryStatus.DONE`).

## Dependencies

### Internal
- `repository/cve/schema`
- `configurations/JpaConfiguration.kt` (bean wiring)
- `application/src/main/resources/db/migration/V14__add_cve_bot_tables.sql`,
  `V15__add_cve_collect_ledger_table.sql`, `V16__add_cve_event_status_created_at_index.sql`,
  `V17__add_cve_event_query_indexes.sql`

### External
Spring Data JPA / Hibernate, MariaDB (`INSERT IGNORE`, `CURRENT_TIMESTAMP(6)`, `LOCALTIMESTAMP(6)`), H2 in tests.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
