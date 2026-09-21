<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# infrastructure/impl/command/event

## Purpose
The Slack-side event vocabulary that rides the in-process bus and the outbox: the rendered
`SlackEventPayload` family, the `CommandEvent` envelopes that carry them (`SendSlackMessageEvent`,
`OpenViewEvent`) or carry an unrendered message (`OutboundMessageEnqueued`), the `MessageDispatcher` port,
and the `CommandOutput` helpers.

## Key Files
| File | Description |
|------|-------------|
| `SlackEventPayloads.kt` | `sealed class SlackEventPayload(apiAppId, commandDetailType, idempotencyKey, publisherId, channel) : EventPayload`; `PostEventPayloadContents(eventId, …, messageType, replaceOriginal, body: Map<String, Any>)`; `ActionEventPayloadContents(…, responseUrl, body: String)`; `OpenViewPayloadContents(…, triggerId, viewJson, meetingIdempotencyKey?, participantUserId = "")`; `enum MessageType { CHANNEL_ALERT, EPHEMERAL_MESSAGE, DIRECT_MESSAGE, ACTION_RESPONSE, UPDATE_MESSAGE }`; `toMessageTypeByTargetUser(targetUserId?)` |
| `SlackCommandEvents.kt` | `SendSlackMessageEvent(idempotencyKey, payload: SlackEventPayload, destination, timestamp, type, isInternal = true)`; `OpenViewEvent(idempotencyKey, payload: OpenViewPayloadContents, type, isInternal = true, destination = "")` |
| `OutboundMessageEnqueued.kt` | `OutboundMessageEnqueuedPayload(eventId, message: OutboundMessage, basicInfo)` and `OutboundMessageEnqueued(idempotencyKey, payload, isInternal = true, type = SIMPLE_TEXT)` — the transport-neutral effect a BEFORE_COMMIT listener turns into an outbox row |
| `MessageDispatcher.kt` | `interface MessageDispatcher { dispatch(SlackEventPayload): CommandOutput; dispatchImmediate(OpenViewPayloadContents): CommandOutput }` |
| `SlackCommandOutputs.kt` | `failOutput(event, reason)` (`Status.FAILED`, `CommandType.SIMPLE`) and `successOutput(payload, commandType, messageTs = "")` |

## For AI Agents

### Working In This Directory
- **`OpenViewEvent` is the only event here that is actually published on the bus** (by the stager via
  `CommandExecutor`, consumed by `SlackViewOpenDispatcher`). `SendSlackMessageEvent` is built by
  `SlackApiEventConstructor` and immediately unwrapped — the renderer returns `.payload` and the relay
  hands that `SlackEventPayload` to `MessageDispatcher.dispatch`. `OutboundMessageEnqueued` is what the
  stager emits for every non-modal family.
- **`PostEventPayloadContents.body` is typed `Map<String, Any>` but always holds form strings**
  (`RequestFormBuilder.toForm` output); the dispatcher calls `toString()` on each value. Keep it flat —
  nested values would be stringified with Kotlin's default `toString`.
- **`MessageType.ACTION_RESPONSE` is never assigned by the constructor**; action responses are
  `ActionEventPayloadContents`. The dispatcher throws if it meets a post payload with that type.
  `UPDATE_MESSAGE` selects `chat.update` and requires `channel` + `ts` in the body.
- **`OutboundMessageEnqueued.type` is inert** (`SIMPLE_TEXT` default) because the event is internal and
  the real routing type is inside the encoded message; do not read it for dispatch decisions.
- `successOutput`'s `commandType` distinguishes Slack Web API calls (`EXTERNAL_API`) from `response_url`
  posts (`RESPONSE`); `messageTs` is populated only from `chat.postMessage` responses.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.impl.command.SlackApiEventConstructorTest' \
  --tests 'dev.notypie.impl.command.SlackOutboundRendererTest'
```
No spec targets this package directly; the constructor and renderer specs assert the payload shapes, and
`testFixtures/.../impl/command/event/SlackEventTestFixtures.kt` builds `SendSlackMessageEvent`s for the
`:application` relay and service specs.

### Common Patterns
- `sealed class` payloads with `open val`s overridden by data-class constructor properties.
- `CommandEvent` implementations default `name` to the class simple name and `timestamp` to now.

## Dependencies

### Internal
- `domain/command/entity/event` — `CommandEvent`, `EventPayload`; `domain/command/dto` —
  `CommandBasicInfo`, `CommandOutput`, `Status`; `domain/command/entity` — `CommandType`,
  `CommandDetailType`; `domain/command/outbound/OutboundMessage`

### External
None.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
