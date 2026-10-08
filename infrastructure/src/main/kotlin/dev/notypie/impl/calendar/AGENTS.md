<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-10-07 | Updated: 2026-10-08 -->

# infrastructure/impl/calendar

## Purpose
Google-side adapters for the per-user calendar connection and its mirror: the OAuth 2.0 client (authorization
URL, code exchange, access-token refresh, token revoke) and the Calendar v3 events client (insert / patch /
delete on the user's primary calendar), both over the JDK `HttpClient`, and the AES-GCM cipher that protects
stored refresh tokens. No Google client library — the OAuth endpoints are plain form POSTs with JSON answers and
the events endpoints plain JSON requests.

## Key Files
| File | Description |
|------|-------------|
| `GoogleOAuthClient.kt` | `(clientId, clientSecret, redirectUri, requestTimeout, authorizationEndpoint, tokenEndpoint, revokeEndpoint, maxBodyBytes = 64 KiB)`; the endpoint defaults are Google's, overridden only by tests. `authorizationUrl(state)`: `response_type=code`, `SCOPES` = `calendar.events openid email`, `access_type=offline`, `prompt=consent` (forces a refresh token on every consent), percent-encoded with `%20` for spaces. `exchangeCode(code): GoogleTokenGrant(accessToken, refreshToken, expiresInSeconds, scopes, subject, email)` (`toString` prints neither token; `grants(scope)` checks the space-separated `scope` field Google returns, which lists only what the user actually ticked under granular consent — `CALENDAR_EVENTS_SCOPE`; a response with no `scope` field at all is read as "the requested scopes" per RFC 6749 §5.1 and logged at WARN; `subject` is the id_token `sub`, the stable account id the connection service compares on reconnect) — `grant_type=authorization_code` form POST; non-2xx → `GoogleOAuthException(message with Google's `error`, statusCode, error)` (`error` is Google's code, `null` when the body names none), non-JSON → the same with the status and no code, transport failure → the same without a status, interrupts rethrown; a 2xx without `access_token` or `refresh_token` also throws so no connection is ever stored without a long-lived credential. `refresh(refreshToken): GoogleAccessToken(accessToken, expiresInSeconds)` (`toString` omits the token) — `grant_type=refresh_token` form POST with the client credentials and no `redirect_uri`, through the same `postForm`, so a revoked grant surfaces as `GoogleOAuthException` with `error == "invalid_grant"` and status 400; a 2xx without `access_token` throws, and a 2xx without a positive `expires_in` is taken as 3600 s (Google's documented lifetime) with one WARN, so the caller's cache never holds a token that is already "expired". `subject` and `email` are read from the `id_token` payload without signature verification (display only: it came from Google's token endpoint over TLS; a malformed payload yields `null`). `revoke(token): Boolean` — `true` on 2xx and on a 400 whose JSON body carries `error=invalid_token` (`ALREADY_INVALID_TOKEN_ERROR`: the token is already gone, nothing left to revoke); any other 400 (`invalid_request`, a non-JSON body), any other status or a transport failure is `false` (logged, never thrown), which is what makes the worker send the manual-removal hint. Bodies are read through `sendWithinDeadline` (`impl/cve/SourceAdapter.kt`) so a stalled body cannot hang the caller |
| `GoogleCalendarClient.kt` | `(requestTimeout, baseUrl = "https://www.googleapis.com/calendar/v3", maxBodyBytes = 64 KiB)`. `insert(accessToken, eventId, event)` = `POST {base}/calendars/primary/events` with `"id": eventId` in the body (the caller chooses the id so a repeated insert is refused instead of duplicated), `patch(accessToken, eventId, event)` = `PATCH …/events/{eventId}` (id percent-encoded, `%20` for spaces), `delete(accessToken, eventId)` = `DELETE …/events/{eventId}`; every request carries `Authorization: Bearer` and goes through `sendWithinDeadline`. `CalendarEventBody(summary, description, start, end, timeZone, meetingUid)` is serialized with `jsonMapper` from maps: `id` (insert only; a patch addresses the event by its path), `status` = `confirmed` (always: Google keeps an event the user deleted as `status: cancelled` and answers 200 to a PATCH on it, so without this a reschedule would "succeed" on an invisible event; with it the mirror is resurrected), `summary`, `description`, `start`/`end` `{dateTime: ISO_LOCAL_DATE_TIME (no offset), timeZone: zone id}`, `extendedProperties.private.codecompanionMeetingUid` (`MEETING_UID_PROPERTY`). Results are a `sealed interface CalendarApiResult`: `Ok(eventId)` (insert/patch read `id` from the 2xx body; delete returns the id it deleted), `Gone` (404 or 410, logged at INFO), `AlreadyExists` (409: an event with the inserted id is already in the calendar, logged at INFO; the sync worker patches it), `Unauthorized` (401), `RateLimited(retryAfter: Duration?)` (429, or 403 whose reasons include `rateLimitExceeded` / `userRateLimitExceeded` / `quotaExceeded`; a numeric `Retry-After` is read as seconds and capped at 24 hours, anything else is `null`), `Misconfigured(message)` (403 whose reasons include `accessNotConfigured` or `SERVICE_DISABLED`: the Calendar API is disabled for the OAuth client's Google Cloud project, a deployment fault the worker treats like a rejected client; the message names the reason and keeps Google's `error.message`, which carries the project and the console link; a 403 `PERMISSION_DENIED` without either reason stays `Failed`), `Failed(statusCode?, message)` (any other status with Google's `error.message` or `HTTP <status>`, a 2xx insert/patch body that is not JSON or has no `id`, a transport failure with `statusCode = null`). The reasons are read from both `error.errors[].reason` (the Calendar v3 legacy list) and `error.details[].reason` (the `google.rpc.ErrorInfo` entries, where `SERVICE_DISABLED` appears). Nothing is thrown except `InterruptedException`, which restores the flag first. Failures log at WARN with the status and Google's `error.message` |
| `TokenCipher.kt` | `TokenCipher(keyBase64)`: the key must decode (standard base64) to exactly 32 bytes, else `IllegalArgumentException` naming the size. `encrypt` → `v1.<iv>.<ciphertext>` (base64url, random 12-byte IV, 128-bit tag, AES/GCM/NoPadding); `decrypt` rejects an unknown version/shape, a wrong IV length, a tampered body or a foreign key with `IllegalArgumentException` (the `AEADBadTagException` is wrapped) |

## For AI Agents

### Working In This Directory
- **`prompt=consent` is deliberate.** Without it Google omits `refresh_token` on a repeat consent, and the
  connection would be useless after the first hour. Do not "optimise" it away.
- **Never log a token.** `exchangeCode` and `refresh` log nothing on success; failures log Google's `error` code
  only. `GoogleCalendarClient` logs neither the access token nor a 2xx body.
- **Read Jackson 3 arrays through `values()`.** `JsonNode` is `Iterable<JsonNode>`, but its member
  `map(Function)` applies the function to the node itself and wins over Kotlin's `Iterable.map`; `.map { }` on
  an array node silently maps the array once. `mapNotNull` / `filter` have no member twin and iterate as expected.
- **`GoogleCalendarClient` decides nothing about retries.** It only classifies the response; backoff, token
  refresh on `Unauthorized` and "`Gone` on delete counts as done" belong to the sync worker in `:application`.
- **Key rotation is not supported in place.** Changing `token-encryption-key` makes every stored token
  undecryptable. On the user's next sync, `GoogleAccessTokenProvider` reports `Revoked` with
  `RevocationCause.TOKEN_UNREADABLE`, and the sync worker marks the connection `REVOKED` and sends the
  `TOKEN_UNREADABLE` reconnect DM. The revocation worker, handed such a token on disconnect, skips the revoke and
  DMs the manual-removal hint. A rotation would need a re-encrypt pass, not a second key.
- The revoke endpoint takes the refresh token; revoking it also kills the access tokens minted from it.
- **A 400 from `/revoke` is not a success by itself.** Google answers 400 `invalid_request` for a malformed
  call as well; only `invalid_token` means the token is already dead. Treating every 400 as "revoked" would
  swallow a client bug and leave the grant alive without telling the user.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.impl.calendar.*'
```
`GoogleOAuthClientTest` and `GoogleCalendarClientTest` run a loopback `com.sun.net.httpserver.HttpServer` and
assert the form fields or JSON body, headers, paths, URL encoding and every failure branch; `TokenCipherTest`
covers round trip, IV randomness, tamper, wrong key, bad format and bad key size.

## Dependencies

### Internal
- `infrastructure/common/JsonMapper.kt` — `jsonMapper`
- `infrastructure/impl/cve/SourceAdapter.kt` — `sendWithinDeadline`, `SourceResponse`
- Consumers: `application/configurations/CalendarConfiguration` (beans), `application/service/calendar/CalendarConnectionService`;
  `refresh` serves `application/service/calendar/GoogleAccessTokenProvider` and `GoogleCalendarClient` serves
  `CalendarSyncService`

### External
JDK `java.net.http`, `javax.crypto`, Jackson 3, kotlin-logging.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
