<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-10-07 | Updated: 2026-10-08 -->

# infrastructure/repository/calendar

## Purpose
Persistence for the per-user Google Calendar connection: one row per Slack user holding the encrypted
refresh token (`google_calendar_connection`), the single-use OAuth `state` ledger that guards the
redirect against CSRF and replay (`google_oauth_state`), and the mirror sync queue
(`meeting_calendar_event`, one dirty-marker row per meeting and connected user). All three adapters are
registered in `configurations/JpaConfiguration`.

## Key Files
| File | Description |
|------|-------------|
| `GoogleCalendarConnectionRepository.kt` | Port: `find(userId): CalendarConnection?`, `saveActive(userId, googleSubject, googleEmail, encryptedRefreshToken, now): ConnectionSaved(connection, replaced: CalendarConnection?)` (insert or rewrite in place — status back to `ACTIVE`, `revoked_at`/`last_error` cleared; the row a rewrite replaced comes back as a snapshot so the caller can decide, by subject/e-mail, whether to revoke its grant at Google), `delete(userId): Boolean`, `hasActiveConnection(userId): Boolean` (`true` only for an `ACTIVE` row; a `REVOKED` row or no row is `false`), `markRevoked(userId, observedEncryptedRefreshToken, now, reason): Boolean` (`ACTIVE` → `REVOKED` with `revoked_at`, `last_error` and `updated_at`, only while the row still holds the ciphertext the caller refreshed with; `false` when there is no row, it is not `ACTIVE`, or a reconnect replaced the token) |
| `GoogleCalendarConnectionRepositoryImpl.kt` | `open class`, `@Transactional` per method; `markRevoked` is the JPA repository's single `UPDATE`, `true` when it changed one row, with `updated_at` bound as `LocalDateTime.ofInstant(now, ZoneId.systemDefault())` (the JVM-zone wall time `@UpdateTimestamp` writes; a bulk JPQL `UPDATE` bypasses that annotation); `saveActive` is find-then-save under the unique `slack_user_id` key (one row per user). Two first-time callbacks racing both see no row and the loser's insert fails on the key with `DataIntegrityViolationException`; the adapter does not catch it — `CalendarConnectionService.completeConnection` retries the whole save transaction once, and the retry's `find` sees the winner's row and rewrites it |
| `JpaGoogleCalendarConnectionRepository.kt` | `findBySlackUserId`, derived `deleteBySlackUserId(): Long`, derived `existsBySlackUserIdAndStatus(slackUserId, status): Boolean` (one indexed lookup on the unique `slack_user_id`), `@Modifying` JPQL `markRevoked(userId, observedEncryptedRefreshToken, now, updatedAt, reason): Int` (`SET status = REVOKED, revokedAt, lastError, updatedAt WHERE slackUserId AND status = ACTIVE AND encryptedRefreshToken = :observed`; enum literals spelled out, so `NativeQueryStatusLiteralTest` does not apply) |
| `GoogleOAuthStateRepository.kt` | Port: `issue(state, userId, expiresAt)`, `consume(state, now): String?` (the Slack user id, once), `deleteExpired(before): Int`, `deleteForUser(userId): Int` (every state of that user, consumed or not — called on `disconnect` so an earlier consent link cannot reconnect the account afterwards) |
| `GoogleOAuthStateRepositoryImpl.kt` | `consume` = native CAS `UPDATE … SET consumed_at = :now WHERE state = :state AND consumed_at IS NULL AND expires_at > :now`, then the row's `slackUserId` only when exactly one row changed; a replayed, expired or unknown state returns `null` without a read-then-write race |
| `JpaGoogleOAuthStateRepository.kt` | The `consume` native update, and `deleteExpiredBefore` / `deleteBySlackUser` as bulk JPQL `DELETE`s (a derived `deleteBy…` loads rows and leaves the removes pending until flush) |
| `MeetingCalendarEventRepository.kt` | `data class CalendarMeetingView(meetingId, meetingUid, title, reason, startAt, endAt, isCanceled, hostId, attendingUserIds)` (`endAt` is non-null; `loadSyncView` resolves it) and the queue port: `enqueue(meetingId, slackUserId, now)` (`Unit`: the upsert's affected-row count differs between MariaDB and H2), `touchMeeting(meetingId, now): Int`, `touchMeetingByUid(meetingUid, now): Int`, `touchOne(meetingId, slackUserId, now): Boolean`, `touchForUser(userId, meetingIds, now): Int` (the user's rows of the listed meetings, `FAILED` included, through an explicit id list rather than a `meetings` subselect; the connect back-fill's revive), `findDue(now, limit): List<Long>`, `claim(id, token, now): MeetingCalendarEvent?` (the row as the claim left it, `null` when the claim missed), `find(id): MeetingCalendarEvent?`, `markSynced(id, token, observedSeq, googleEventId, now)`, `deleteSynced(id, token, observedSeq)`, `releaseWithoutEvent(id, token, now)`, `retryLater(id, token, observedSeq, attempts, nextAttemptAt, lastError, now)` (all `Boolean`), `markFailed(id, token, observedSeq, attempts, lastError, now): CalendarSyncStatus?` (the status the row was left in: `FAILED` with the caller's `attempts`, or `PENDING` when a hook moved `change_seq` after the claim; `null` when the claim was lost), `resetStuck(olderThan, now): Int`, `failPendingForUser(userId, lastError, now): Int`, `loadSyncView(meetingId): CalendarMeetingView?` |
| `MeetingCalendarEventRepositoryImpl.kt` | `open class` over `JpaMeetingRepository` + `JpaMeetingCalendarEventRepository`, built with the `PlatformTransactionManager`. The hook methods (`enqueue`, the four touches, `failPendingForUser`) are `@Transactional` and join the caller's transaction; `findDue`, `find` and `loadSyncView` are `@Transactional(readOnly = true)`. The worker transitions (`claim`, `markSynced`, `deleteSynced`, `releaseWithoutEvent`, `retryLater`, `markFailed`, `resetStuck`) carry no annotation: each starts with `check(!TransactionSynchronizationManager.isActualTransactionActive())`, so inside a transaction it throws `IllegalStateException` before any SQL, and then runs its statements in its own `TransactionTemplate` transaction. `enqueue` is the single native upsert with `created_at` / `updated_at` bound as `LocalDateTime.ofInstant(now, ZoneId.systemDefault())`, the JVM-zone wall time `@CreationTimestamp` writes. `claim` runs the CAS and, when it matched, `findById` in the same transaction and returns that snapshot, so the worker's `change_seq` is the one its claim saw. `markFailed` runs the CAS and reads the row's status back in the same transaction. Booleans are `rowCount == 1`. `touchMeetingByUid` binds `meetingUid.toString()`. `touchForUser` returns 0 without a statement for an empty `meetingIds` (an empty `IN ()` list is engine-dependent). `loadSyncView` projects `findMeetingWithParticipants` (the `LEFT JOIN FETCH` read): `reason` is `MeetingSchema.reason ?: ""` (`toMeetingDto` deliberately blanks it); `endAt` is the meeting's end only when it is after the start, otherwise start + 1 h (no end, or an inverted range such as the rows V21 reset, which Google would refuse); `hostId` is the publisher; `attendingUserIds` holds only `isAttending` participants; a missing meeting gives `null` |
| `JpaMeetingCalendarEventRepository.kt` | JPQL `findDueIds(now, pageable)` (`PENDING`, `nextAttemptAt <= :now`, `nextAttemptAt` then `id` ascending); native `upsertDirty(meetingId, slackUserId, now, createdAt)`, an `INSERT … ON DUPLICATE KEY UPDATE` on `uk_meeting_calendar_event_meeting_user`: a new pair is inserted `PENDING` at `change_seq` 1, due at `:now`; an existing one gets `change_seq + 1`, `attempts = 0`, due at `:now`, and `PENDING` plus a new `updated_at` unless it is `SYNCING`. Native `@Modifying` CAS: `claim` (`PENDING` and due → `SYNCING` + token), `markSynced` (owner's `SYNCING` row → `SYNCED` when `change_seq = :observedSeq`, else `PENDING` due at `:now`; writes `google_event_id` either way, resets `attempts`/`last_error`/`claim_token`), `deleteSynced` (`DELETE` only while the token and `change_seq` both match), `releaseWithoutEvent` (owner's `SYNCING` row → `PENDING`, `google_event_id = NULL`), `retryLater` and `markFailed` (owner's `SYNCING` row; with `change_seq` still at `:observedSeq` → `PENDING` with the attempt count and backoff, or `FAILED` with the attempt count; with it moved → `PENDING`, `attempts = 0`, due at `:now`; `last_error` written and token cleared either way), `resetStuckSyncing(olderThan, now)`; the four hook bumps `touchByMeetingId` / `touchByMeetingUid` (subselect on `meetings.meeting_uid`) / `touchOne` / `touchBySlackUserId` (`slack_user_id = :slackUserId AND meeting_id IN (:meetingIds)`, no read of `meetings`), each `change_seq + 1`, `attempts = 0`, due at `:now`, and `PENDING` plus `updated_at = :now` unless `SYNCING`; native `failAllPendingForUser` (that user's `PENDING` rows → `FAILED`, token cleared) |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `schema/` | `GoogleCalendarConnectionSchema` + `CalendarConnection` DTO, `GoogleOAuthStateSchema`, `CalendarConnectionStatus`, `MeetingCalendarEventSchema` + the `MeetingCalendarEvent` read model, `CalendarSyncStatus` (see `schema/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- **The state consume is the only CSRF/replay guard.** Keep it one atomic `UPDATE` keyed on
  `consumed_at IS NULL AND expires_at > now`; a `find` followed by a `save` would let two callbacks with the
  same state both succeed.
- **Tokens are opaque here.** `encryptedRefreshToken` is whatever `TokenCipher` produced; nothing in this
  package decrypts, validates or logs it, and `CalendarConnection.toString()` omits it.
- Column names, types and constraint names must match `db/migration/V24__add_google_calendar_tables.sql` and
  `V25__add_meeting_calendar_event.sql`.
- **A `meeting_calendar_event` row is a dirty marker, not a command.** It says "look at this (meeting, user)
  again"; the worker derives insert / patch / delete from `loadSyncView` at sync time. Do not add a "desired
  action" column: a queued action is wrong as soon as a cancel and an accept commit around each other.
- **`change_seq` is the generation CAS.** Every hook bumps it; a `SYNCING` row keeps its status, claim and
  `updated_at`, so the in-flight worker still owns it and the stuck sweep still ages it from the claim. Every
  completion compares the seq of the snapshot `claim` returned. `markSynced`: equal → `SYNCED`, moved → `PENDING`
  due at once, and the event id is stored in both cases so the next pass patches or deletes the event this call
  wrote. `markFailed` / `retryLater`: equal → `FAILED` / the backoff (both store the caller's attempt count), moved → `PENDING`, attempts 0, due at once,
  so a cancel or reschedule that arrives during the last attempt is still synced instead of being buried under
  `FAILED` or a long backoff. A delete that Google confirmed but whose `deleteSynced` missed on the seq must call
  `releaseWithoutEvent`, never leave the stale id behind.
- **Every native transition binds `updated_at` from the caller's clock**, never `CURRENT_TIMESTAMP`, for the
  reason the reminder lane records (`repository/meeting/AGENTS.md`): `resetStuckSyncing` compares against an
  application `Instant`. The touches and the enqueue upsert leave a `SYNCING` row's `updated_at` alone, so a hook
  firing during a stuck call cannot keep that claim young. Rows inserted through JPA and both branches of the
  upsert write the JVM-zone wall time (`LocalDateTime`); the sweep reads only `SYNCING` rows, whose `updated_at`
  their claim wrote.
- **No `SET` assignment reads a column an earlier assignment in the same statement wrote.** MariaDB evaluates
  single-table `UPDATE … SET` and `ON DUPLICATE KEY UPDATE` left to right with the new values, H2 with the old
  ones. The touches and the upsert assign `updated_at` first because its `CASE` reads `status`, and only then
  `status`, whose `CASE` reads itself; the `CASE`s of `markSynced`, `markFailed` and `retryLater` read only
  `change_seq`, which none of them assigns, or the column they assign. Keep that true when adding a column to these
  statements, or H2 will pass what MariaDB gets wrong.
- **`meeting_uid` is bound as text in `touchByMeetingUid`.** A native query has no mapped attribute to derive
  the UUID binding from; `V2` declares the column `CHAR(36)`, the string compares on MariaDB, and H2 converts
  it against its native `UUID` column (the spec covers that). Hibernate 7.4's `MariaDBDialect` would bind a
  `UUID` as a string too, but only for server versions it detects as 10.7 or later.
- **`failPendingForUser` fails `PENDING` rows only.** It runs in the worker's revocation transaction, only after
  `markRevoked` matched there, and in the slash transaction of `disconnect` (`last_error` "disconnected"). A `SYNCING`
  row belongs to its worker, which records its own outcome (`markSynced`, or `markFailed` on its own row when it meets
  `Revoked` / `NotConnected`); failing it from outside would make the owner's `markSynced` miss after an insert Google
  accepted. Nothing deletes a user's rows on disconnect: the `SYNCED` and `FAILED` rows are what a later connect's
  `touchForUser` revives when that connect's range read returns their meeting (`PENDING`, attempts 0, due at once; a `SYNCING` row only gets its seq bumped, so its owner's
  completion requeues it), and the events already mirrored stay in their calendar.
- **Worker transitions refuse an outer transaction; hooks join one.** `claim` must commit before the Google call
  so other replicas and the stuck sweep see it, and every later transition commits on its own. Inside a caller's
  transaction a transition issued after a plain read would also risk the 1020 below. The hook methods are the
  opposite: they must commit with the meeting write that caused them.
- **Queue writes issued after a plain read in the same transaction can fail with 1020** under production
  MariaDB's snapshot isolation (REPEATABLE READ with `innodb_snapshot_isolation` ON), the mechanism the reminder
  lane records in `repository/meeting/AGENTS.md`. That covers the touches, the upsert (its row and its foreign-key
  check on `meetings`) and `failPendingForUser`, which all run in a transaction that has usually read something
  first. The cancel and reschedule hooks sit inside the meeting write that `executeRetryingOnConflict`
  retries on 1020. The accept / decline hook runs at `BEFORE_COMMIT` of the attendance write, outside that retry:
  its foreign-key check on `meetings` fails the Accept with 1020 when the host changed the meeting after the
  interaction transaction's snapshot, and the user clicks again. That millisecond window is accepted (the reminder
  lane documents the same mechanism), because moving the hook to `AFTER_COMMIT` would need a second connection.
  Two Accepts of the same user are serialized by the `meeting_participants` row lock; the second hook runs again
  whatever the driver reports for its `UPDATE` (a zero-row result with an existing participant still calls it) and
  merely bumps `change_seq` once more. The first Accepts of two different users no longer deadlock: the
  upsert is one statement, with no pre-`UPDATE` whose gap lock the other insert waits on. A connected user's accept
  costs three statements (the connection exists-check, the meeting id lookup, the upsert). The accept / decline hook
  also fails with 1020 through its own row (the upsert, or `touchOne` on Decline) when the worker wrote that row
  after the interaction transaction's snapshot. The other accepted 1020 windows: the connect back-fill's hosted
  upsert, through its foreign-key check on `meetings` (the meeting changed after the store transaction's snapshot) or
  its own row, and `disconnect`'s `failPendingForUser` and the back-fill's revive `touchForUser`, when the worker
  wrote one of the user's rows after the slash or store transaction's snapshot; the user runs `disconnect` again, and
  the callback retries its whole store once and answers `STORE_FAILED` when the retry conflicts too. The revive no
  longer reads `meetings`: `touchForUser` takes the explicit ids of the back-fill's read, because a `meetings`
  subselect inside an `UPDATE` is a locking read under REPEATABLE READ, and with no index leading on `start_at` it
  would hold shared locks on every meetings row it scanned until the store commits, blocking concurrent meeting
  writes and failing with 1020 on any of those rows changed after the snapshot.
- **The Google event id is chosen by the worker, so inserts are idempotent.** `mirroredEventIdOf(meetingUid,
  slackUserId)` in `application/service/calendar` is the lowercase hex SHA-256 of `uid:user` (64 base32hex
  characters), so two Slack users who connected one Google account get two events; a second insert for the same
  meeting and user into the same calendar answers 409, which the worker turns into a patch. `google_event_id` is therefore
  only a "was created" marker, and a stuck-sweep reset of a live claim cannot duplicate an event. Arithmetic for
  the 10-minute `sync-stuck-minutes`: a worker's longest path is two token refreshes and six Google calls (patch →
  404 → insert → 409 → patch, repeated once after a 401), each bounded by `request-timeout-seconds` (10 s), so about
  80 s. A reset after a successful insert is harmless: the next pass inserts the same id, gets 409 and patches.
- **`markRevoked` is one guarded `UPDATE`, never load-mutate-save.** The table has no version column, so a
  dirty-checked save would write every column and put the old token back over a reconnect that committed in
  between. The `UPDATE` sets `updatedAt` itself, since a bulk statement bypasses `@UpdateTimestamp`. The `encryptedRefreshToken = :observed` clause is the version: a revocation observed on a grant that a
  reconnect has since replaced matches nothing. A bulk JPQL `UPDATE` bypasses the persistence context; callers in
  the same transaction must not trust an entity they loaded before it.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.calendar.*'
```
`@DataJpaTest` specs on H2 construct the `*Impl` adapters by hand over the autowired `Jpa*` repositories:
consume once / replay / expired / unknown, per-user delete leaving other users' states, bulk purge, upsert-in-place
and delete, the active-connection check and `markRevoked`. `JpaMeetingCalendarEventRepositoryTest` runs on its own
`MODE=MariaDB` H2 database (the native upsert needs it) and covers every queue transition: the losing paths of each
CAS (foreign token, replay, stale seq, the `SYNCING` status guard, a token the stuck sweep cleared), the moved-seq
path of `markSynced`, `markFailed` and `retryLater`, the upsert's insert, update and `SYNCING` branches, and the
worker guard (see the test directory's `AGENTS.md`). H2 does not check the MariaDB `SET` evaluation order, the 1020
snapshot conflicts, affected-row counts of the upsert or the `CHAR(36)` uid comparison; verify a change there
against a real database.

## Dependencies

### Internal
- `configurations/JpaConfiguration` — registers the three adapters (the queue adapter with the
  `PlatformTransactionManager`)
- `repository/meeting/JpaMeetingRepository` (`findMeetingWithParticipants`) and
  `repository/meeting/schema/MeetingSchema` (the queue row's FK)
- Consumers: `application/service/calendar/CalendarConnectionService` (also `MeetingCalendarEventRepository.failPendingForUser`
  on disconnect, and `enqueue` plus `touchForUser`, its only caller, for the connect back-fill), `GoogleAccessTokenProvider` (`find`),
  `MeetingCalendarMirrorService` (`hasActiveConnection`, the queue's `enqueue` / touches) and `CalendarSyncService`
  (`markRevoked`, the queue's claim and transitions, and `hasActiveConnection` plus `enqueue`, in a transaction of its
  own, to re-queue a pair whose claim it lost after a Google write)

### External
Spring Data JPA, Hibernate.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
