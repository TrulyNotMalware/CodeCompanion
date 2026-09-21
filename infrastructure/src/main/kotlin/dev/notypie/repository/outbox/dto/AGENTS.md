<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# infrastructure/repository/outbox/dto

## Purpose
Result events the relay raises after a dispatch attempt so the status-updater listener can move an outbox
row from `IN_PROGRESS` to its terminal state. One file, one sealed hierarchy.

## Key Files
| File | Description |
|------|-------------|
| `OutboxMessageEvents.kt` | `sealed class OutboxUpdateEvent(eventId: UUID, status: MessageStatus)`; `MessagePublishFailedEvent(eventId, reason)` → `FAILURE`; `MessagePublishSuccessEvent(eventId, messageTs = "")` → `SUCCESS`; `fun CommandOutput.toOutboxUpdateEvent(eventId: UUID): OutboxUpdateEvent` picks by `ok`, carrying `messageTs` or `errorReason` |

## For AI Agents

### Working In This Directory
- **The status is fixed by the subclass**, not by the caller: a third outcome means a new
  `OutboxUpdateEvent` subclass with its own `MessageStatus`, never a mutable status field.
- **`eventId` is a `UUID` here but a `String` (`event_id`) on the row.** Consumers bridge with `toString()`
  / `UUID.fromString` at the boundary; do not change the row PK type to match.
- `messageTs` on the success event is the Slack `chat.postMessage` ts and defaults to `""` when the call
  returns none; treat blank as "unknown", not as a ts.
- Raised by `SlackMessageRelayServiceImpl` and `DebeziumLogTailingProcessor` in `:application` and consumed
  by the outbox status listener there.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.outbox.*'
```
No spec targets this file in `:infrastructure`; the `ok` / `!ok` branch of `toOutboxUpdateEvent` is covered
from `:application` relay specs.

### Common Patterns
- Sealed base with `open val`s, data-class leaves with `override val`s.
- Extension function on the domain DTO (`CommandOutput`) rather than a factory on the event.

## Dependencies

### Internal
- `domain/command/dto/response/CommandOutput`
- `repository/outbox/schema/MessageStatus`

### External
None beyond the Kotlin stdlib.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
