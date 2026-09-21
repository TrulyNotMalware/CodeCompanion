<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# infrastructure/src/testFixtures/kotlin/dev/notypie/impl/command/event

## Purpose
Creators for the infrastructure command events in main `impl/command/event/`: the Slack send / open-view
events and their payload contents. Used wherever a spec needs a stub `SendSlackMessageEvent` to return from a
mocked `SlackApiEventConstructor` or an `OpenViewEvent` to hand to a dispatcher.

## Key Files
| File | Description |
|------|-------------|
| `SlackEventTestFixtures.kt` | `createPostEventPayloadContents(commandDetailType, targetUserId = null, appId, publisherId, channel, idempotencyKey = random, body = {})` — `messageType` from `toMessageTypeByTargetUser(targetUserId)`, `replaceOriginal = false`; `createActionEventPayloadContents(commandDetailType, body = "", appId, publisherId, channel, idempotencyKey, responseUrl = TEST_BASE_URL)`; `createSendSlackMessageEvent(commandDetailType, idempotencyKey, isPostEventPayload = true, targetUserId = null, messageType: MessageType? = null, appId, publisherId, channel)` — picks post vs action payload, optional `messageType` override, `destination = ""`; `createOpenViewEvent(idempotencyKey, appId, publisherId, channel, commandDetailType = MEETING_DECLINE_REASON, triggerId = "", meetingIdempotencyKey, participantUserId, viewJson = "{}")` |

## For AI Agents

### Working In This Directory
- **`createSendSlackMessageEvent` ties the `idempotencyKey` on the event and on the payload together**; pass
  the same key a spec's `basicInfo` carries so `payload shouldBe stubEvent.payload` style assertions stay
  meaningful.
- `destination = ""` makes the stub internal-only; a spec asserting Kafka routing must define its own event
  type with a real destination (see `KafkaEventPublisherTest`'s local `TestKafkaCommandEvent`).
- `createOpenViewEvent` defaults to the decline-reason flow with an empty `viewJson`;
  `SlackOutboundStagerTest` only reads `commandDetailType`, so override that and nothing else unless the
  view JSON matters.
- `createPostEventPayloadContents` / `createActionEventPayloadContents` are only called through
  `createSendSlackMessageEvent` today; they stay public for `:application` use.
- Consumed by `SlackOutboundRendererTest`, `SlackOutboundStagerTest` here and by several `:application`
  service specs through `testFixtures(project(":infrastructure"))`.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.impl.command.SlackOutboundRendererTest'
./gradlew :infrastructure:test --tests 'dev.notypie.impl.command.SlackOutboundStagerTest'
```

### Common Patterns
- Top-level `create*` functions with `:domain` constant defaults and a random `eventId` per call.
- `.let { if (messageType != null) it.copy(messageType = messageType) else it }` override pattern.

## Dependencies

### Internal
- `infrastructure/src/main/kotlin/dev/notypie/impl/command/event/` — `SendSlackMessageEvent`,
  `OpenViewEvent`, `PostEventPayloadContents`, `ActionEventPayloadContents`, `OpenViewPayloadContents`,
  `MessageType`, `toMessageTypeByTargetUser`
- `domain/command/entity/CommandDetailType`; `domain/src/testFixtures/.../Constants.kt`

### External
None.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
