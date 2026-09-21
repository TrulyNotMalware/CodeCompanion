<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# infrastructure/src/test/kotlin/dev/notypie/repository/outbox

## Purpose
Specs for the outbox codec in main `repository/outbox/`. One file here (the codec) and one in `schema/` (the
row builder). Plain Kotest, no Spring, no database.

## Key Files
| File | Description |
|------|-------------|
| `OutboundMessageCodecTest.kt` | `OutboundMessageCodec.encode` / `decode` through an `OutboundEnvelope(message, createCommandBasicInfo())`. The only `StringSpec` in the module. Round-trips every registered subtype: `ChannelMessage` with `Text` (with / without `detailType`, with `threadId`), `ErrorNotice`, `Schedule` (compared field by field because `TimeScheduleInfo.timeFormatter` has no `equals`), `Form` (string select values, `TextInputContents`, `ApprovalContents`), `MeetingRequest` (approval and null), `StandupSummary` (`ZoneId` + `Instant` inside DTOs); `Ephemeral` with `Text` and `MeetingList`; `Approval` with `routingExtras`; `Notice`; `UpdateMessage`; `ReplaceMessage`. Fail-fast: an `OpenModal` round-trip, an unknown `@type`, and malformed JSON all throw `OutboundMessageCodecException` |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `schema/` | `OutboxMessageTest` — `CodecOutboundMessagePort.toRow` and `updateMessageStatus` (see `schema/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- **Every new outbox-bound `OutboundMessage` or `MessageContent` subtype gets an `assertRoundTrips` case
  here** in the same change that registers it in the codec mix-ins; a subtype missing from the mix-in fails
  at encode, which this spec turns into a red test instead of a stuck row in production.
- **`OpenModal` failing is a feature.** The "not outbox-bound" case must stay; do not register `OpenModal`
  or `DirectMessage` to make it pass.
- Whole-envelope `shouldBe` equality is the default assertion; drop to field-wise only for types whose
  `equals` is unreliable (today only `TimeScheduleInfo`) and say why in the case name.
- **Coverage gap**: `MessageOutboxRepository` (claim CAS, `findStuckInProgress`, health counters) has no H2
  spec anywhere in the module. A `@DataJpaTest` here is the missing `Jpa*RepositoryTest` half of the lane.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.outbox.*'
```
No Spring context; runs in milliseconds.

### Common Patterns
- `StringSpec` with a `roundTrip(message)` helper and an `assertRoundTrips(message)` wrapper.
- Domain fixtures `createCommandBasicInfo`, `createApprovalContents`, `createMeetingDto`,
  `createRoutineMemberDto`, `createStandupAnswerDto` from `:domain` test fixtures.

## Dependencies

### Internal
- `infrastructure/src/main/kotlin/dev/notypie/repository/outbox/` — codec, envelope, port, `Transport`
- `domain/command/outbound`, `domain/command/dto/modals`, `domain/command/entity/CommandDetailType`
- `domain/src/testFixtures/kotlin/dev/notypie/domain/`

### External
Kotest, Jackson 3 (through the codec).

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
