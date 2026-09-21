<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# application/controllers/dto

## Purpose
Controller-layer response classes. Both files are dead today: no controller, service, or test
references them, and `SlackEventController.handleAppMentionEvents` returns the domain `CommandOutput`
directly while every slash endpoint returns an empty 200.

## Key Files
| File | Description |
|------|-------------|
| `CodeCompanionResponse.kt` | `data class CodeCompanionResponse(ok: Boolean = true, message: String)` — generic ok/message envelope, unreferenced |
| `ResponseDto.kt` | `data class EventResponseDto(message: String, event: Event, isAccepted: Boolean)` and `data class Event(eventId: UUID, type: CommandDetailType, acceptedTime: Long)` — an accepted-event acknowledgement shape, unreferenced |

## For AI Agents

### Working In This Directory
- Before using either class, check the Slack contract: Slack expects an empty 200 (or a
  `response_action` JSON for `view_submission`) from the endpoints in `../`, so a JSON body on those
  routes is ignored at best. These DTOs only make sense for a non-Slack API surface.
- If a class is picked up, add a `@WebMvcTest` slice asserting the serialized shape; the REST Docs DSL in
  `src/testFixtures/kotlin/dev/notypie/docs/` exists for documenting exactly this kind of response.
- Otherwise prefer deleting them over letting them drift — `Event.type` couples this package to
  `domain/command/entity/CommandDetailType` for no runtime benefit.

### Testing Requirements
```bash
./gradlew :application:test
```
No spec exercises these classes; there is nothing to run until they gain a call site.

### Common Patterns
- Immutable Kotlin `data class`es with defaults for optional flags (`ok = true`).

## Dependencies

### Internal
- `domain/command/entity/CommandDetailType` — `Event.type`

### External
JDK `UUID` only.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
