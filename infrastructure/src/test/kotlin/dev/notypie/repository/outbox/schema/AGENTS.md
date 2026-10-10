<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-10-08 -->

# infrastructure/src/test/kotlin/dev/notypie/repository/outbox/schema

## Purpose
Spec for the outbox row builder and entity in main `repository/outbox/schema/`. Plain Kotest `BehaviorSpec`,
no Spring, no database — it checks the in-memory `OutboxMessage` the port produces, not persistence.

## Key Files
| File | Description |
|------|-------------|
| `OutboxMessageTest.kt` | `toChainHead` of three parts → payload with part 1 and parts 2–3 as continuation, `chainedParts() == 2`, schema V3; of one part → V2, `chainedParts() == 0`. A `ReplaceMessage` with a `fallback` → V4 and the fallback decodes back; without one → V2; `SUPPORTED == {2, 3, 4}`. `CodecOutboundMessagePort().toRow(message, basicInfo)`: `eventId` non-blank and unique per call, `idempotencyKey` / `publisherId` copied from `createCommandBasicInfo()`, `transport == Transport.SLACK.name`, `schemaVersion == OutboxSchemaVersion.V2`, `status == MessageStatus.PENDING.name`, and `payload` decodes back through `OutboundMessageCodec` to the original `basicInfo` and a `ChannelMessage` / `Text`. `OutboxMessage.updateMessageStatus` to `SUCCESS` and `FAILURE` |

## For AI Agents

### Working In This Directory
- A new column on `OutboxMessage` with a non-trivial default (like `schemaVersion`) belongs in the "stamped
  at the current schema version" `then`, which asserts a plain row against `OutboxSchemaVersion.V2` (the
  constant, not a literal); a chain head's V3 is asserted in the chain `given`.
- `status` is asserted as `MessageStatus.X.name` because the column is a `String`; do not change the
  assertion to compare enums.
- `toOutboxMessage()` (the Debezium `Map` → entity path with micro-epoch timestamps) is **not** covered here
  or anywhere in the module.

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
