<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-10-08 | Updated: 2026-10-11 -->

# infrastructure/src/testFixtures/kotlin/dev/notypie/impl/calendar

## Purpose
Builders for the request and response shapes of the Google Calendar events client and the Google OAuth token
endpoint, used by `test/.../impl/calendar/GoogleCalendarClientTest` and `GoogleOAuthClientTest` and available to
`:application` sync-worker specs.

## Key Files
| File | Description |
|------|-------------|
| `GoogleCalendarFixtures.kt` | `createCalendarEventBody(summary = "Sprint review", description = "Demo the release", start = 2031-01-06T15:00, end = 2031-01-06T16:00, timeZone = Asia/Seoul, meetingUid = fixed)`; `createGoogleApiErrorJson(code, reason, message)` — the Calendar v3 error envelope `{"error": {"code", "message", "errors": [{"domain": "usageLimits", "reason", "message"}]}}` whose `reason` drives the 403 rate-limit classification; `createGoogleServiceDisabledErrorJson(legacyReason = "accessNotConfigured", errorInfoReason = "SERVICE_DISABLED", status = "PERMISSION_DENIED", message = …)` — the 403 body for a disabled Calendar API, shaped after Google's documented error model (an `errors[]` legacy entry and a `details[]` `google.rpc.ErrorInfo` entry; pass `null` to leave either out), not captured from a live response; `createGoogleTokenResponseJson(accessToken = "ya29.access", refreshToken = "1//refresh", expiresIn = 3599, idToken = null, scope = "openid https://www.googleapis.com/auth/calendar.events email")` — a token-endpoint response for both the code exchange and the refresh grant, `token_type` always `Bearer`, `null` leaves a field out. `expiresIn` defaults to 3599 so a parsed lifetime stays distinguishable from the client's 3600 s fallback |

## For AI Agents

### Working In This Directory
- `createGoogleApiErrorJson` and `createGoogleTokenResponseJson` interpolate their arguments into a JSON
  template without escaping; pass plain text without quotes or backslashes.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.impl.calendar.*'
```

## Dependencies

### Internal
- `infrastructure/src/main/kotlin/dev/notypie/impl/calendar/GoogleCalendarClient.kt` — `CalendarEventBody`

### External
None.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
