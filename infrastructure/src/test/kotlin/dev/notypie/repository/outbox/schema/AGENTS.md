<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# infrastructure/src/test/kotlin/dev/notypie/repository/outbox/schema

## Purpose
Spec for the outbox row builder and entity in main `repository/outbox/schema/`. Plain Kotest `BehaviorSpec`,
no Spring, no database — it checks the in-memory `OutboxMessage` the port produces, not persistence.

## Key Files
| File | Description |
|------|-------------|
| `OutboxMessageTest.kt` | `CodecOutboundMessagePort().toRow(message, basicInfo)`: `eventId` non-blank and unique per call, `idempotencyKey` / `publisherId` copied from `createCommandBasicInfo()`, `transport == Transport.SLACK.name`, `schemaVersion == OutboxSchemaVersion.CURRENT`, `status == MessageStatus.PENDING.name`, and `payload` decodes back through `OutboundMessageCodec` to the original `basicInfo` and a `ChannelMessage` / `Text`. `OutboxMessage.updateMessageStatus` to `SUCCESS` and `FAILURE` |

## For AI Agents

### Working In This Directory
- A new column on `OutboxMessage` with a non-trivial default (like `schemaVersion`) belongs in the "stamped
  at the current schema version" `then`; bumping `OutboxSchemaVersion.CURRENT` must keep this spec green
  without edits because it asserts against the constant, not a literal.
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
