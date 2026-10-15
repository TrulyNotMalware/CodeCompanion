<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-10-02 | Updated: 2026-10-15 -->

# test/kotlin/dev/notypie/application/controllers

## Purpose
HTTP contract specs for the Slack controllers in main `controllers/`, on a standalone `MockMvc` (no Spring context)
with the real `ControllerAdvice` and MockK services.

## Key Files
| File | Description |
|------|-------------|
| `SlackControllersTest.kt` | `SlackEventController` + `SlashCommandController`: an `app_mention` whose command failed with an internal exception text → `200` with an empty body; `CommandOutput.empty()` (an ignored mention) → no `WARN` from the controllers package (read with `captureLogs` on that package), a failed command → exactly `app_mention command failed: <reason>` at `WARN`; a reason carrying `\n` and ESC → the same WARN with `?` in their place (fails when the controller logs the reason raw); `url_verification` with extra fields → only `{"challenge": ...}` echoed; `/api/slash/meet` posted as a form with `Accept: application/json` → `200` and `handleMeeting` once (the mapping is selected by `consumes`); the same endpoint posted as JSON → `415`. All four failed on the controllers before 2026-10-02. `/api/slash/calendar` posted as a `/calendar connect` form → `200` and `CalendarSlashService.handleCalendar` once with that command and text (and `["connect"]` as the parsed sub-commands), never `handleMeeting`. Deferral: for each of `/meet`, `/standup`, `/calendar`, `/subscribe`, `/unsubscribe`, `/subscriptions`, `/latest` the mocked service publishes an `OpenViewEvent` through a real `SlackViewOpenDispatcher`, and `views.open` (`MessageDispatcher.dispatchImmediate`) has not run when the service returns and has run once after the request, so dropping an endpoint's `ViewOpenDeferral.afterBoundary` fails its case; `POST /api/slash/ask` with `/ask` form text → only `AskSlashService.handleAsk` is called, with the question as its tokens |
| `GoogleOAuthCallbackControllerTest.kt` | `GoogleOAuthCallbackController` on a standalone `MockMvc` with a MockK `CalendarConnectionCallback`: each of the eight outcomes' status, title text and `Cache-Control: no-store` (`STORE_FAILED` → 503 "The connection could not be saved", "This link cannot be used again", `text/html`; `ACCOUNT_MISMATCH` → 403 "This Google account does not match your Slack account" with the sign-in hint; `IDENTITY_UNAVAILABLE` → 503 "Your Slack e-mail could not be checked", "This link cannot be used again"; neither page contains an `@`); a `<script>` state and the `STORE_FAILED` and `ACCOUNT_MISMATCH` requests' `code` / `state` are never echoed; a `RuntimeException` whose message carries SQL text → 500 `text/html` with `no-store`, "The connection could not be completed" and "Run /calendar connect in Slack for a new one.", never `internal_error`, the exception text or a request parameter (the standalone `MockMvc` registers the real `ControllerAdvice`, so the `text/html` and `internal_error` assertions test the controller-local handler's precedence over the advice's JSON 500); an `InterruptedException` → the same 500 page and the thread's interrupt flag set afterwards (read and cleared with `Thread.interrupted()` right after the request, so it fails when the handler stops restoring it) |

## For AI Agents

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.controllers.*'
```
The signature filter is not in a standalone `MockMvc`; its behaviour is covered by `security/SlackRequestVerificationFilterTest`.
Slash form bodies come from `testFixtures/.../controllers/SlashCommandFormCreator.kt`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
