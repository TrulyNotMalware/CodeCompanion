<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-19 -->

# application/socket

## Purpose
The local-only inbound transport. `SocketModeReceiver` opens a Slack Socket Mode WebSocket and feeds
slash commands, interactive payloads, and Events API callbacks into the same service interfaces the
HTTP controllers use, so a developer needs no public URL, tunnel, or signing secret. Outbound traffic
is unchanged (Web API via the outbox relay). The bean exists only under the `local` Spring profile.

## Key Files
| File | Description |
|------|-------------|
| `SocketModeReceiver.kt` | `@Component @Profile("local") class SocketModeReceiver(appConfig, meetingService, standupSlashService, cveSubscriptionSlashService, cveQuerySlashService, calendarSlashService, askSlashService, interactionHandler, appMentionEventHandler) : SmartLifecycle`. `start()` skips with a warning when `slack.app.api.app-token` is blank, else builds `Slack.getInstance().socketMode(appToken)`, registers three envelope listeners and connects. `handleSlash(payloadJson)` (internal, the slash-failure test seam) parses the envelope map through `parseRequestBodyData(headers = noHeaders, data)` and dispatches on `payload.command` against `AppConfig.Socket` (`meetingCommand` → `handleMeeting`, `standupCommand` → `handleStandup`, `calendarCommand` (`/calendar`; `application-local.yaml` reads `SLACK_CALENDAR_COMMAND`) → `handleCalendar`, `askCommand` (`/ask`; `application-local.yaml` reads `SLACK_ASK_COMMAND`) → `AskSlashService.handleAsk`, `subscribeCommand` / `unsubscribeCommand` / `subscriptionsCommand` → the CVE subscription service, `latestCommand` → `handleLatest`); `dispatchSlash(payload, commandData)` (internal, the slash test seam); `handleInteractive(payloadJson, acknowledge)` (internal, the test seam) passes the `InteractionHandler` ack body to `acknowledge`, and on a handler exception logs ERROR and sends **no** ack; `handleEvent(payloadJson)` (internal, test seam) forwards only `event.type == "app_mention"`. `stop()` disconnects. `start()`, `stop()`, `handleSlash` and `handleEvent` run their bodies in `containFailure` (infrastructure `common`): an `Exception` is logged, an interrupt is rethrown with its flag, an `Error` propagates (2026-10-15); `stop()` clears `client` in a `finally`, so an `Error` from `disconnect()` still leaves `isRunning()` false. Each of the three envelope listeners runs inside `logErrorThenRethrow(kind, envelopeId)` (internal): an `Error` is logged at ERROR with the envelope id and rethrown. The Slack SDK (1.52.0) runs listeners on ten `scheduleAtFixedRate` message loops that catch only `Exception`, so an `Error` silently ends that loop for good; the log line is the only trace, and after ten of them Socket Mode handles nothing until the app restarts |

## For AI Agents

### Working In This Directory
- Slash and event envelopes are acked immediately, then handled. Interactive envelopes are handled
  first because a `view_submission` may need its `response_action` JSON inside the ack; that body is
  spliced into a raw `{"envelope_id":...,"payload":...}` string since `AckResponse` cannot carry one.
- No signature verification runs on this path (the WebSocket is authenticated by the app-level token),
  and `noHeaders` is an empty `MultiValueMap`. Never widen the `@Profile` — enabling this bean in a
  deployed profile bypasses `security/SlackRequestVerificationFilter`.
- The `when (payload.command)` branch list (`dispatchSlash`) is the Socket Mode twin of `SlashCommandController` and, like it,
  runs inside `ViewOpenDeferral.afterBoundary` so staged modals open after the service transaction released its connection. A new
  slash command needs a mapping here, a property in `AppConfig.Socket`, and the controller route; an
  unmapped command is only logged as a warning.
- Slash and event failures are `containFailure` + `log.error` after the ack — there is no `ControllerAdvice` on
  this transport, so a bug that would return 400/500 over HTTP is invisible there except in the log. A failed
  interactive envelope is not acked at all, so Slack shows the user an error (as it does for the HTTP route's
  500) instead of closing the modal as if the rolled-back click or submission had succeeded.
- The receiver is gated on the `local` profile (there is no `socket` profile). `application-local.yaml` and `docs/wiki/dev-environment.md` document the `local` profile and the
  `SLACK_APP_TOKEN` (`connections:write`) requirement.
- `start()` never throws an `Exception`: a bad token or a connect failure is logged and `isRunning()` stays false,
  so the app boots without Socket Mode rather than failing. An `Error` there fails the context start.

### Testing Requirements
```bash
./gradlew :application:test
```
`socket/SocketModeReceiverTest` covers `handleInteractive` (empty ack, `response_action` body ack, no ack
on failure), the slash routing through `dispatchSlash`, and `handleSlash` / `handleEvent` over a throwing service
(an `Exception` is contained, an `Error` propagates); `start()` / `stop()` wrap a live `SocketModeClient` and have no
spec. Handler behaviour is covered by the
service specs (`MeetingServiceImplTest`, `CveSubscriptionSlashServiceImplTest`,
`CveQuerySlashServiceImplTest`, `SlackInteractionHandlerImplTest`, `SlackMentionEventHandlerImplTest`).
`handleSlash` / `handleEvent` take the JSON string, so a routing case can assert with MockK service interfaces that
the right method receives `headers = noHeaders`. Manual
check: run with `--spring.profiles.active=local` and a `SLACK_APP_TOKEN`, then issue each slash command
in Slack.

### Common Patterns
- `SmartLifecycle` with a `@Volatile` client field so Spring starts/stops the socket with the context.
- The same `(headers, payload, commandData)` service signature as the controllers, always with named
  parameters.
- `private const val` for the event type and file-level `KotlinLogging.logger {}`.

## Dependencies

### Internal
- `application/common/SlackRequestParser` — `parseRequestBodyData(headers, data)`
- `application/configurations/AppConfig` — `api.appToken`, `Socket.*Command`
- `application/service/meeting/MeetingService`, `service/standup/StandupSlashService`, `service/agent/AskSlashService`,
  `service/cve/subscription/CveSubscriptionSlashService`, `service/cve/query/CveQuerySlashService`,
  `service/interaction/InteractionHandler`, `service/mention/AppMentionEventHandler`
- `application/controllers/` — the HTTP twin of every branch here
- `infrastructure/common/JsonMapper.kt` — `jsonMapper`

### External
Slack `slack-api-client` (`Slack`, `SocketModeClient`, `AckResponse`) with the tyrus WebSocket runtime,
Spring `SmartLifecycle` / `@Profile`, kotlin-logging.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
