<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-10-02 | Updated: 2026-10-08 -->

# test/kotlin/dev/notypie/application/socket

## Purpose
Spec for the local-only Socket Mode receiver's interactive ack contract.

## Key Files
| File | Description |
|------|-------------|
| `SocketModeReceiverTest.kt` | `SocketModeReceiver` built with `AppConfig()` and MockK services, driven through the internal `handleInteractive(payloadJson, acknowledge)` seam with a recording `acknowledge`: a `null` ack body → one ack without a body; a `response_action` JSON body → that body rides the ack; a handler that throws → no ack at all. Slash mapping, through the internal `dispatchSlash(payload, commandData)` seam with a `createSlashCommandForm(command = "/calendar", text = "status")` payload: only `CalendarSlashService.handleCalendar` is called (the default `AppConfig.Socket.calendarCommand`), never the meeting or standup service |

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
