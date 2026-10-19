<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-10-02 | Updated: 2026-10-19 -->

# test/kotlin/dev/notypie/application/socket

## Purpose
Spec for the local-only Socket Mode receiver's interactive ack contract.

## Key Files
| File | Description |
|------|-------------|
| `SocketModeReceiverTest.kt` | `SocketModeReceiver` built with `AppConfig()` and MockK services, driven through the internal `handleInteractive(payloadJson, acknowledge)` seam with a recording `acknowledge`: a `null` ack body → one ack without a body; a `response_action` JSON body → that body rides the ack; a handler that throws → no ack at all. Slash mapping, through the internal `dispatchSlash(payload, commandData)` seam with a `createSlashCommandForm(command = "/calendar", text = "status")` payload: only `CalendarSlashService.handleCalendar` is called (the default `AppConfig.Socket.calendarCommand`), never the meeting or standup service; a `createSlashCommandForm(command = "/ask", ...)` payload → only `AskSlashService.handleAsk` is called (the default `AppConfig.Socket.askCommand`); a custom `askCommand` name (`/question`) still routes to `AskSlashService`, and an unmapped name calls no service. Failures, on a receiver with its own MockK `CalendarSlashService` and `AppMentionEventHandler`: `handleSlash` with a JSON-serialised `/calendar` form whose service throws `IllegalStateException` → the service called once, nothing thrown, exactly the ERROR `Socket Mode slash-command handling failed.`; `StackOverflowError` → the same instance propagates; `handleEvent` with a JSON-serialised `createAppMentionPayload()` whose handler throws the same two → the same outcomes (ERROR `Socket Mode event handling failed.`). `logErrorThenRethrow(kind, envelopeId)`: an `Error` → one ERROR naming the kind and envelope id, then the same instance rethrown; an `Exception` → passes through with no ERROR (the SDK loop logs and survives those) |

## For AI Agents

### Working In This Directory
- The receiver's `start()` builds a live `SocketModeClient`; never call it here. Test only through the
  internal seams.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.socket.*'
```

## Dependencies

### Internal
- `application/socket/SocketModeReceiver`, `application/service/interaction/InteractionHandler`,
  `application/configurations/AppConfig`

### External
Kotest `BehaviorSpec`, MockK.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
