<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# application/socket

## Purpose
The local-only inbound transport. `SocketModeReceiver` opens a Slack Socket Mode WebSocket and feeds
slash commands, interactive payloads, and Events API callbacks into the same service interfaces the
HTTP controllers use, so a developer needs no public URL, tunnel, or signing secret. Outbound traffic
is unchanged (Web API via the outbox relay). The bean exists only under the `local` Spring profile.

## Key Files
| File | Description |
|------|-------------|
| `SocketModeReceiver.kt` | `@Component @Profile("local") class SocketModeReceiver(appConfig, meetingService, standupSlashService, cveSubscriptionSlashService, cveQuerySlashService, interactionHandler, appMentionEventHandler) : SmartLifecycle`. `start()` skips with a warning when `slack.app.api.app-token` is blank, else builds `Slack.getInstance().socketMode(appToken)`, registers three envelope listeners and connects. `handleSlash` parses the envelope map through `parseRequestBodyData(headers = noHeaders, data)` and dispatches on `payload.command` against `AppConfig.Socket` (`meetingCommand` → `handleMeeting`, `standupCommand` → `handleStandup`, `subscribeCommand` / `unsubscribeCommand` / `subscriptionsCommand` → the CVE subscription service, `latestCommand` → `handleLatest`); `handleInteractive` returns the `InteractionHandler` ack body; `handleEvent` forwards only `event.type == "app_mention"`. `stop()` disconnects |

## For AI Agents

### Working In This Directory
- Slash and event envelopes are acked immediately, then handled. Interactive envelopes are handled
  first because a `view_submission` may need its `response_action` JSON inside the ack; that body is
  spliced into a raw `{"envelope_id":...,"payload":...}` string since `AckResponse` cannot carry one.
- No signature verification runs on this path (the WebSocket is authenticated by the app-level token),
  and `noHeaders` is an empty `MultiValueMap`. Never widen the `@Profile` — enabling this bean in a
  deployed profile bypasses `security/SlackRequestVerificationFilter`.
- The `when (payload.command)` branch list is the Socket Mode twin of `SlashCommandController`. A new
  slash command needs a mapping here, a property in `AppConfig.Socket`, and the controller route; an
  unmapped command is only logged as a warning.
- Failures are `runCatching` + `log.error` — there is no `ControllerAdvice` on this transport, so a
  bug that would return 400/500 over HTTP is invisible here except in the log.
- The build script comment describes this as gated to a `socket` profile; the code gates on `local`.
  `application-local.yaml` and `docs/wiki/dev-environment.md` document the `local` profile and the
  `SLACK_APP_TOKEN` (`connections:write`) requirement.
- `start()` never throws: a bad token or a connect failure is logged and `isRunning()` stays false, so
  the app boots without Socket Mode rather than failing.

### Testing Requirements
```bash
./gradlew :application:test
```
No spec covers the receiver (it wraps a live `SocketModeClient`). Handler behaviour is covered by the
service specs (`MeetingServiceImplTest`, `CveSubscriptionSlashServiceImplTest`,
`CveQuerySlashServiceImplTest`, `SlackInteractionHandlerImplTest`, `SlackMentionEventHandlerImplTest`).
To test the routing, extract `handleSlash` / `handleEvent` behind a seam that takes the JSON string and
assert with MockK service interfaces that the right method receives `headers = noHeaders`. Manual
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
- `application/service/meeting/MeetingService`, `service/standup/StandupSlashService`,
  `service/cve/subscription/CveSubscriptionSlashService`, `service/cve/query/CveQuerySlashService`,
  `service/interaction/InteractionHandler`, `service/mention/AppMentionEventHandler`
- `application/controllers/` — the HTTP twin of every branch here
- `infrastructure/common/JsonMapper.kt` — `jsonMapper`

### External
Slack `slack-api-client` (`Slack`, `SocketModeClient`, `AckResponse`) with the tyrus WebSocket runtime,
Spring `SmartLifecycle` / `@Profile`, kotlin-logging.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
