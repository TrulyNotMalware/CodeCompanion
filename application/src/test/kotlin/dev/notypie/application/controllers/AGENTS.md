<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-10-02 | Updated: 2026-10-02 -->

# test/kotlin/dev/notypie/application/controllers

## Purpose
HTTP contract specs for the Slack controllers in main `controllers/`, on a standalone `MockMvc` (no Spring context)
with the real `ControllerAdvice` and MockK services.

## Key Files
| File | Description |
|------|-------------|
| `SlackControllersTest.kt` | `SlackEventController` + `SlashCommandController`: an `app_mention` whose command failed with an internal exception text → `200` with an empty body; `url_verification` with extra fields → only `{"challenge": ...}` echoed; `/api/slash/meet` posted as a form with `Accept: application/json` → `200` and `handleMeeting` once (the mapping is selected by `consumes`); the same endpoint posted as JSON → `415`. All four failed on the controllers before 2026-10-02. Deferral: for each of `/meet`, `/standup`, `/subscribe`, `/unsubscribe`, `/subscriptions`, `/latest` the mocked service publishes an `OpenViewEvent` through a real `SlackViewOpenDispatcher`, and `views.open` (`MessageDispatcher.dispatchImmediate`) has not run when the service returns and has run once after the request, so dropping an endpoint's `ViewOpenDeferral.afterBoundary` fails its case |

## For AI Agents

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.controllers.*'
```
The signature filter is not in a standalone `MockMvc`; its behaviour is covered by `security/SlackRequestVerificationFilterTest`.
Slash form bodies come from `testFixtures/.../controllers/SlashCommandFormCreator.kt`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
