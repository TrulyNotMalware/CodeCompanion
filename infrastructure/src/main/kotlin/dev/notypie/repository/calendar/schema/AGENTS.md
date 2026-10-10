<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-10-07 | Updated: 2026-10-08 -->

# infrastructure/repository/calendar/schema

## Purpose
JPA entities for the Google Calendar connection store, the OAuth state ledger and the mirror sync queue, plus
the read models the application layer sees.

## Key Files
| File | Description |
|------|-------------|
| `GoogleCalendarConnectionSchema.kt` | `@Entity(name = "google_calendar_connection")`, `uk_google_calendar_connection_user` on `slack_user_id`. Columns: `slack_user_id` (64), `google_subject` (255, nullable, the id_token `sub`), `google_email` (255, nullable), `refresh_token_enc` (TEXT, the `TokenCipher` output), `status` (`CalendarConnectionStatus`, STRING 16), `connected_at` / `revoked_at` (`Instant`), `last_error` (TEXT), `created_at` / `updated_at`. Mutator `reconnect(googleSubject, googleEmail, encryptedRefreshToken, now)` (back to `ACTIVE`, clears revoke fields); the setters are `protected`. Revocation is not a mutator: `JpaGoogleCalendarConnectionRepository.markRevoked` is a conditional JPQL `UPDATE` (see `../AGENTS.md`). `toCalendarConnection()` maps to the DTO; `data class CalendarConnection` carries every column but its `toString` omits the token; `isActive` |
| `GoogleOAuthStateSchema.kt` | `@Entity(name = "google_oauth_state")`, `state` (64) is the primary key; `slack_user_id`, `expires_at`, `consumed_at` (`Instant`), `created_at`; index `idx_google_oauth_state_expires_at` for the purge |
| `CalendarConnectionStatus.kt` | `ACTIVE`, `REVOKED` (written by the sync worker when Google rejects the refresh token or the stored token cannot be decrypted, `RevocationCause.GRANT_REJECTED` / `TOKEN_UNREADABLE`; the user must reconnect) |
| `MeetingCalendarEventSchema.kt` | `@Entity(name = "meeting_calendar_event")`, unique `uk_meeting_calendar_event_meeting_user` on `(meeting_id, slack_user_id)`, indexes `idx_meeting_calendar_event_status_next_attempt` and `idx_meeting_calendar_event_user`. `@ManyToOne(LAZY)` `meeting` (`meeting_id`, not null, FK `fk_meeting_calendar_event_meeting` with `@OnDelete(CASCADE)`, as `V25` declares it, so a schema Hibernate generates also drops the queue rows with their meeting), `slack_user_id` (64), `google_event_id` (1024, nullable), `status` (`CalendarSyncStatus`, STRING 16), `change_seq` (`Long`, starts at 1), `attempts`, `next_attempt_at` (`Instant`), `claim_token` (36), `last_error` (TEXT), `created_at` / `updated_at`. Every property is a `val`: all transitions are native statements in `JpaMeetingCalendarEventRepository`. `toMeetingCalendarEvent()` maps to `data class MeetingCalendarEvent(id, meetingId, slackUserId, googleEventId?, status, changeSeq, attempts, nextAttemptAt, lastError?)` (no claim token); it reads `meeting.id`, so call it inside a transaction |
| `CalendarSyncStatus.kt` | `PENDING` (due at `next_attempt_at`), `SYNCING` (claimed; `claim_token` names the owner), `SYNCED`, `FAILED` (retries exhausted, grant revoked, not connected, or disconnected). The native queries quote these names; `NativeQueryStatusLiteralTest` maps the repository to this enum |

## For AI Agents

### Working In This Directory
- `connected_at`, `revoked_at`, `expires_at`, `consumed_at` are `Instant` (UTC, like `meeting_reminder.scheduled_at`);
  only the audit `created_at` / `updated_at` are `LocalDateTime` as everywhere else.
- Renaming a `CalendarConnectionStatus` or `CalendarSyncStatus` value breaks persisted rows (and, for the
  latter, the quoted literals in the native queries). Add values, never rename.
- `meeting_calendar_event.next_attempt_at` is an `Instant` like the other calendar timestamps; `V25` declares it and
  the audit columns `DATETIME(6)`, like `V24`, with no database default or `ON UPDATE` clause.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
