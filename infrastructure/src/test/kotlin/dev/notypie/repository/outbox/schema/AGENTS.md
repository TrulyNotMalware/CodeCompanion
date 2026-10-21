<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-10-20 -->

# infrastructure/src/test/kotlin/dev/notypie/repository/outbox/schema

## Purpose
Spec for the outbox row builder and entity in main `repository/outbox/schema/`. Plain Kotest `BehaviorSpec`,
no Spring, no database — it checks the in-memory `OutboxMessage` the port produces, not persistence.

## Key Files
| File | Description |
|------|-------------|
| `OutboxMessageTest.kt` | `toChainHead` of three parts → payload with part 1 and parts 2–3 as continuation, `chainedParts() == 2`; head and a one-part chain (`chainedParts() == 0`) both V7. A `ReplaceMessage` with a `fallback` → the fallback decodes back; with and without one → V7; `SUPPORTED == {2, 3, 4, 5, 6, 7}`. `CodecOutboundMessagePort().toRow(message, basicInfo)`: `eventId` non-blank and unique per call, `idempotencyKey` / `publisherId` copied from `createCommandBasicInfo()`, `transport == Transport.SLACK.name`, `schemaVersion == OutboxSchemaVersion.V7`, `status == MessageStatus.PENDING.name`, the payload's `basicInfo` goes from `appId` straight to `publisherId` with no `appToken` key, and `payload` decodes back through `OutboundMessageCodec` to the original `basicInfo` and a `ChannelMessage` / `Text`. `OutboxMessage.updateMessageStatus` to `SUCCESS` and `FAILURE`; an `AgentQuestion` row and a plain row → V7. A `DirectReply` fallback on an `/ask` question, an `Ephemeral` or a plain `ChannelMessage`, the staged direct reply itself, a fallback or an `AgentQuestion` only in a continuation part, a plain chain and a fallback nested in a `ReplaceMessage`'s fallback ephemeral → all V7 (2026-10-20; before, V2–V6 by the highest part) |

## For AI Agents

### Working In This Directory
- A new column on `OutboxMessage` with a non-trivial default (like `schemaVersion`) belongs in the "stamped
  V7 and starts PENDING" `then`, which asserts a plain row against `OutboxSchemaVersion.V7` (the
  constant, not a literal); the other shapes' V7 is asserted in their own `given`s.
- `status` is asserted as `MessageStatus.X.name` because the column is a `String`; do not change the
  assertion to compare enums.
- `toOutboxMessage()` (the Debezium `Map` → entity path) is covered here for the timestamp decoding under a non-UTC
  JVM zone and for failures: a `HashMap` whose `get` throws `IllegalStateException` → a `RuntimeException` whose
  `cause` is that instance, `StackOverflowError` → the same instance, unwrapped.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.outbox.schema.OutboxMessageTest'
```

### Common Patterns
- `BehaviorSpec`; `shouldBeInstanceOf<T>()` to narrow the decoded message before asserting content.

## Dependencies

### Internal
- `infrastructure/src/main/kotlin/dev/notypie/repository/outbox/` — `CodecOutboundMessagePort`,
  `OutboundMessageCodec`, `Transport`, `schema/`
- `domain/src/testFixtures/kotlin/dev/notypie/domain/command/` — `createCommandBasicInfo`

### External
Kotest.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
