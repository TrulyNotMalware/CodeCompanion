<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-10-07 | Updated: 2026-10-07 -->

# infrastructure/repository/calendar/schema

## Purpose
JPA entities for the Google Calendar connection store and the OAuth state ledger, plus the read model the
application layer sees.

## Key Files
| File | Description |
|------|-------------|
| `GoogleCalendarConnectionSchema.kt` | `@Entity(name = "google_calendar_connection")`, `uk_google_calendar_connection_user` on `slack_user_id`. Columns: `slack_user_id` (64), `google_subject` (255, nullable, the id_token `sub`), `google_email` (255, nullable), `refresh_token_enc` (TEXT, the `TokenCipher` output), `status` (`CalendarConnectionStatus`, STRING 16), `connected_at` / `revoked_at` (`Instant`), `last_error` (TEXT), `created_at` / `updated_at`. Mutators `reconnect(googleSubject, googleEmail, encryptedRefreshToken, now)` (back to `ACTIVE`, clears revoke fields) and `revoke(now, reason)`; the setters are `protected`. `toCalendarConnection()` maps to the DTO; `data class CalendarConnection` carries every column but its `toString` omits the token; `isActive` |
| `GoogleOAuthStateSchema.kt` | `@Entity(name = "google_oauth_state")`, `state` (64) is the primary key; `slack_user_id`, `expires_at`, `consumed_at` (`Instant`), `created_at`; index `idx_google_oauth_state_expires_at` for the purge |
| `CalendarConnectionStatus.kt` | `ACTIVE`, `REVOKED` (written when Google rejects the refresh token; the user must reconnect) |

## For AI Agents

### Working In This Directory
- `connected_at`, `revoked_at`, `expires_at`, `consumed_at` are `Instant` (UTC, like `meeting_reminder.scheduled_at`);
  only the audit `created_at` / `updated_at` are `LocalDateTime` as everywhere else.
- Renaming a `CalendarConnectionStatus` value breaks persisted rows. Add values, never rename.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
