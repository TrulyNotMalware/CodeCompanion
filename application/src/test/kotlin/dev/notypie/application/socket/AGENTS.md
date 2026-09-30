<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-09-30 | Updated: 2026-09-30 -->

# test/kotlin/dev/notypie/application/socket

## Purpose
Spec for the local-only Socket Mode receiver's interactive seam. The envelope listeners wrap a live
`SocketModeClient` and are not exercised; only `handleInteractive(payloadJson, acknowledge)` is.

## Key Files
| File | Description |
|------|-------------|
| `SocketModeReceiverTest.kt` | `SocketModeReceiver` built with `AppConfig()`, a MockK `InteractionHandler` and bare MockK slash / mention services. `handleInteractive` with a handler returning `null` → one ack without a body; returning a `response_action` JSON → that body is acked; throwing → no ack at all (Slack then shows the user an error, as the HTTP route's 500 does) |

## For AI Agents

### Working In This Directory
- The "no ack on failure" case is the R3-07 contract; do not go back to acking an empty body after a swallowed
  exception.
- Slash and event envelopes are acked before handling and have no seam here; add one (a function taking the
  JSON string) before testing their routing.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.socket.*'
```

## Dependencies

### Internal
- `application/socket/SocketModeReceiver.kt`, `application/service/interaction/InteractionHandler`,
  `application/configurations/AppConfig`

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
