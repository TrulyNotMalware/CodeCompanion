<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-10-07 | Updated: 2026-10-11 -->

# infrastructure/src/test/kotlin/dev/notypie/impl/calendar

## Purpose
Specs for `impl/calendar`: the Google OAuth client and the Calendar events client against a loopback HTTP
server, and the token cipher.

## Key Files
| File | Description |
|------|-------------|
| `GoogleOAuthClientTest.kt` | Loopback `HttpServer` with a swappable `respond`; captures path, `Content-Type` and the form body, reset before each root `given`. Token-endpoint bodies come from `createGoogleTokenResponseJson`, overriding only the fields a case asserts on. Authorization URL flags/scopes/encoding (`%20`, no `+`); successful exchange (both tokens, `expires_in`, subject and e-mail from a hand-built `id_token`, the scope set, masked `toString`); no `id_token` with a partial `scope` → no subject/e-mail, calendar not granted; no `scope` field at all → the requested scopes; missing `refresh_token` → `GoogleOAuthException`; 400 `invalid_grant` → exception with code and status; non-JSON 504 → exception with status; refresh → access token and `expires_in`, masked `toString`, form fields `client_id`/`client_secret`/`refresh_token`/`grant_type=refresh_token` and no `redirect_uri`; refresh 400 `invalid_grant` → `error == "invalid_grant"`, status 400; refresh 500 `{}` → `error == null`, "unknown" in the message; refresh 2xx without `access_token` → exception; refresh 2xx with no `expires_in` or with `0` → `expiresInSeconds` 3600 and exactly one WARN `Google refresh response has no positive expires_in; assuming 3600s` (Logback `ListAppender` on the client's logger); revoke 200 → `true` with the token as a form field, 400 `invalid_token` → `true`, 400 `invalid_request` → `false`, 400 with a non-JSON body → `false`, 500 → `false` |
| `GoogleCalendarClientTest.kt` | Loopback `HttpServer` capturing method, raw path, `Authorization`, `Content-Type` and body, reset before each root `given`. The caller's event id is a 64-hex example (the shape `mirroredEventIdOf` produces). Insert: `Ok(id)`, bearer-authorized JSON `POST` to `/calendars/primary/events`, body fields `id` = the caller's event id (fails when `"id"` is dropped from the insert body), `status` = `confirmed`, `summary`, `description`, `start`/`end` `dateTime` without offset plus `timeZone`, `extendedProperties.private.codecompanionMeetingUid`; patch: `PATCH` to the percent-encoded event path (`evt%201%2Fx`) → `Ok(id)`, its body also `status` = `confirmed` and carries no `id`; insert answered 409 → `AlreadyExists`; delete 204 → `Ok(deleted id)` with no body or `Content-Type`, 404 and 410 → `Gone`; 401 → `Unauthorized`; 403 `userRateLimitExceeded` → `RateLimited(null)`; 429 with `Retry-After: 7` → `RateLimited(7 s)`; 429 with `Retry-After: 999999999999` → `RateLimited(24 h)`; 403 for a disabled Calendar API (`createGoogleServiceDisabledErrorJson`) with both the legacy `accessNotConfigured` and the ErrorInfo `SERVICE_DISABLED` → `Misconfigured` naming `accessNotConfigured` plus Google's message, with only `SERVICE_DISABLED` (on a patch) → `Misconfigured` naming it, with only `accessNotConfigured` (on a delete) → `Misconfigured`, `PERMISSION_DENIED` with neither reason → `Failed(403, Google's message)`; 403 `forbiddenForNonOrganizer` → `Failed(403, Google's message)`; 500 `{}` → `Failed(500, "HTTP 500")`; 200 without `id` and non-JSON 200 → `Failed(200, …)`; a closed loopback port → `Failed(statusCode = null)`. Request bodies and error JSON come from `testFixtures/.../impl/calendar/GoogleCalendarFixtures.kt` |
| `TokenCipherTest.kt` | Round trip, two encryptions differ, tampered ciphertext (first decoded byte of the third segment XOR-flipped, re-encoded as unpadded base64url) / foreign key / bad format rejected with `IllegalArgumentException`, 16-byte key rejected with the sizing message, non-base64 key rejected |

## For AI Agents

### Working In This Directory
- Tamper with a `TokenCipher` token by changing decoded bytes, never by overwriting base64url characters: the last
  characters of an unpadded segment carry only part of a byte, so an overwrite can reproduce the original token.
- The captured request fields are cleared in `beforeContainer` for root containers only, so a `then` can read
  what its enclosing `given` sent. `beforeTest` would also run before each `then` and wipe it.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.impl.calendar.*'
```
The HTTP stub pattern is the one `impl/cve/GithubReleaseSourceAdapterTest` uses; keep responses tiny so
`sendWithinDeadline`'s body watchdog never fires in the suite.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
