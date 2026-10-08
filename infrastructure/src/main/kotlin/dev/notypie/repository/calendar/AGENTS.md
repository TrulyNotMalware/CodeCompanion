<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-10-07 | Updated: 2026-10-08 -->

# infrastructure/repository/calendar

## Purpose
Persistence for the per-user Google Calendar connection: one row per Slack user holding the encrypted
refresh token (`google_calendar_connection`) and the single-use OAuth `state` ledger that guards the
redirect against CSRF and replay (`google_oauth_state`). Both adapters are registered in
`configurations/JpaConfiguration`.

## Key Files
| File | Description |
|------|-------------|
| `GoogleCalendarConnectionRepository.kt` | Port: `find(userId): CalendarConnection?`, `saveActive(userId, googleSubject, googleEmail, encryptedRefreshToken, now): ConnectionSaved(connection, replaced: CalendarConnection?)` (insert or rewrite in place — status back to `ACTIVE`, `revoked_at`/`last_error` cleared; the row a rewrite replaced comes back as a snapshot so the caller can decide, by subject/e-mail, whether to revoke its grant at Google), `delete(userId): Boolean` |
| `GoogleCalendarConnectionRepositoryImpl.kt` | `open class`, `@Transactional` per method; `saveActive` is find-then-save under the unique `slack_user_id` key (one row per user). Two first-time callbacks racing both see no row and the loser's insert fails on the key with `DataIntegrityViolationException`; the adapter does not catch it — `CalendarConnectionService.completeConnection` retries the whole save transaction once, and the retry's `find` sees the winner's row and rewrites it |
| `JpaGoogleCalendarConnectionRepository.kt` | `findBySlackUserId`, derived `deleteBySlackUserId(): Long` |
| `GoogleOAuthStateRepository.kt` | Port: `issue(state, userId, expiresAt)`, `consume(state, now): String?` (the Slack user id, once), `deleteExpired(before): Int`, `deleteForUser(userId): Int` (every state of that user, consumed or not — called on `disconnect` so an earlier consent link cannot reconnect the account afterwards) |
| `GoogleOAuthStateRepositoryImpl.kt` | `consume` = native CAS `UPDATE … SET consumed_at = :now WHERE state = :state AND consumed_at IS NULL AND expires_at > :now`, then the row's `slackUserId` only when exactly one row changed; a replayed, expired or unknown state returns `null` without a read-then-write race |
| `JpaGoogleOAuthStateRepository.kt` | The `consume` native update, and `deleteExpiredBefore` / `deleteBySlackUser` as bulk JPQL `DELETE`s (a derived `deleteBy…` loads rows and leaves the removes pending until flush) |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `schema/` | `GoogleCalendarConnectionSchema` + `CalendarConnection` DTO, `GoogleOAuthStateSchema`, `CalendarConnectionStatus` (see `schema/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- **The state consume is the only CSRF/replay guard.** Keep it one atomic `UPDATE` keyed on
  `consumed_at IS NULL AND expires_at > now`; a `find` followed by a `save` would let two callbacks with the
  same state both succeed.
- **Tokens are opaque here.** `encryptedRefreshToken` is whatever `TokenCipher` produced; nothing in this
  package decrypts, validates or logs it, and `CalendarConnection.toString()` omits it.
- Column names, types and constraint names must match `db/migration/V24__add_google_calendar_tables.sql`.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.calendar.*'
```
`@DataJpaTest` specs on H2 construct the `*Impl` adapters by hand over the autowired `Jpa*` repositories:
consume once / replay / expired / unknown, per-user delete leaving other users' states, bulk purge, upsert-in-place
and delete.

## Dependencies

### Internal
- `configurations/JpaConfiguration` — registers both adapters
- Consumers: `application/service/calendar/CalendarConnectionService`

### External
Spring Data JPA, Hibernate.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
