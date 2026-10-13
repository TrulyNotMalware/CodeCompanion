<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-10-13 -->

# infrastructure/repository/outbox/dto

## Purpose
The events the relay publishes around the terminal status write of an outbox row: `MessageDeliveredEvent`
before it, when the transport accepted the message, and an `OutboxUpdateEvent` after the write was recorded.
One file: one sealed hierarchy and one standalone event.

## Key Files
| File | Description |
|------|-------------|
| `OutboxMessageEvents.kt` | `sealed class OutboxUpdateEvent(eventId: UUID, status: MessageStatus)`; `MessagePublishFailedEvent(eventId, reason)` → `FAILURE`; `MessagePublishSuccessEvent(eventId, commandDetailType, messageTs = "")` → `SUCCESS`; `fun CommandOutput.toOutboxUpdateEvent(eventId: UUID): OutboxUpdateEvent` picks by `ok`, carrying the dispatch output's `commandDetailType` and `messageTs`, or `errorReason` (the standup summary listener acts only on `STANDUP_SUMMARY`). `data class MessageDeliveredEvent(eventId: UUID, commandDetailType, messageTs)` is not an `OutboxUpdateEvent` and has no status |

## For AI Agents

### Working In This Directory
- **The status is fixed by the subclass**, not by the caller: a third outcome means a new
  `OutboxUpdateEvent` subclass with its own `MessageStatus`, never a mutable status field.
- **`eventId` is a `UUID` here but a `String` (`event_id`) on the row.** Consumers bridge with `toString()`
  / `UUID.fromString` at the boundary; do not change the row PK type to match.
- `messageTs` on the success event is the Slack `chat.postMessage` ts and defaults to `""` when the call
  returns none; treat blank as "unknown", not as a ts.
- All of them are published by `SlackMessageRelayServiceImpl` in `:application`, which writes the status itself.
  An `OutboxUpdateEvent` goes out only after the owner's CAS recorded the outcome; `MessagePublishSuccessEvent`
  has one consumer, `StandupSummaryService` (`STANDUP_SUMMARY` marker swap).
- **`MessageDeliveredEvent` means "accepted by the transport, not yet recorded"** (2026-10-13). It is published
  for an `ok` dispatch before the status write, so it can come from an owner whose claim is then lost, and a
  resend of the row publishes it again with another ts. Do not make it an `OutboxUpdateEvent` subclass: that
  hierarchy promises a recorded outcome. Its consumer is `AgentAskThreadStarter` (`AGENT_ASK_QUESTION` → the
  `/ask` turn ledger row and the turn), which is why it exists: the ledger row must be written before `SUCCESS`.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.outbox.*'
```
No spec targets this file in `:infrastructure`; the `ok` / `!ok` branch of `toOutboxUpdateEvent` and the order of
`MessageDeliveredEvent` against the status write are covered from `:application` relay specs.

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
