<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-09-30 -->

# infrastructure/src/test/kotlin/dev/notypie/repository/outbox

## Purpose
Specs for main `repository/outbox/`: the codec (plain Kotest), the repository's native SQL
(`@DataJpaTest` on H2), and in `schema/` the row builder.

## Key Files
| File | Description |
|------|-------------|
| `OutboundMessageCodecTest.kt` | `OutboundMessageCodec.encode` / `decode` through an `OutboundEnvelope(message, createCommandBasicInfo())`. The only `StringSpec` in the module. Round-trips every registered subtype: `ChannelMessage` with `Text` (with / without `detailType`, with `threadId`), `ErrorNotice`, `Schedule` (compared field by field because `TimeScheduleInfo.timeFormatter` has no `equals`), `Form` (string select values, `TextInputContents`, `ApprovalContents`), `MeetingRequest` (approval and null), `StandupSummary` (`ZoneId` + `Instant` inside DTOs); `Ephemeral` with `Text` and `MeetingList`; `Approval` with `routingExtras`; `Notice`; `UpdateMessage`; `ReplaceMessage`. Fail-fast: an `OpenModal` round-trip, an unknown `@type`, and malformed JSON all throw `OutboundMessageCodecException` |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `schema/` | `OutboxMessageTest` — `CodecOutboundMessagePort.toRow` and `updateMessageStatus` (see `schema/AGENTS.md`) |

`MessageOutboxRepositoryTest.kt` (`@DataJpaTest`, H2) covers the native statements: `findPendingMessages` orders by `created_at`, not insertion (the older row is inserted second and aged with SQL, because `@CreationTimestamp` overwrites a constructor value); `claimPending` loses the second call, moves `attempt_count` to 1 and stores the caller's `now` — a claim stamped far in the future is not reclaimable against real time, which a `CURRENT_TIMESTAMP` write would be; `reclaimStuck` wins once, raises the attempt and loses on a stale attempt; a late worker on attempt 1 can neither `renewClaim` nor `completeClaim` over the attempt-2 owner's `SUCCESS`, and only the owner's renewal counts in `send_count`; `deferClaim` refunds the send, stores the caller's `updatedAt`, loses on a stale attempt and never takes `send_count` below 0; `abandonStuck` leaves a fresh claim alone and affects 0 rows with a token another sweep already moved past; `abandonPending` fails a PENDING row on the observed attempt without raising `attempt_count` and stores the caller's `now`, but affects 0 rows once another reader claimed it; `countInProgressWithSendsAtLeast` counts sends, not claims; `deleteTerminalOlderThan` purges aged SUCCESS/FAILURE only. Rows are aged and read back with `JdbcTemplate`.

## For AI Agents

### Working In This Directory
- **Every new outbox-bound `OutboundMessage` or `MessageContent` subtype gets an `assertRoundTrips` case
  here** in the same change that registers it in the codec mix-ins; a subtype missing from the mix-in fails
  at encode, which this spec turns into a red test instead of a stuck row in production.
- **`OpenModal` failing is a feature.** The "not outbox-bound" case must stay; do not register `OpenModal`
  or `DirectMessage` to make it pass.
- Whole-envelope `shouldBe` equality is the default assertion; drop to field-wise only for types whose
  `equals` is unreliable (today only `TimeScheduleInfo`) and say why in the case name.
- `MessageOutboxRepositoryTest` rows persist across `given` blocks (no per-block rollback): keep
  `findPendingMessages` first, and never stamp a terminal row old enough for the purge case to count it.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.outbox.*'
```
The codec and schema specs need no Spring context; `MessageOutboxRepositoryTest` shares the module's
`@DataJpaTest` H2 context.

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
