<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# domain/command/outbound (test)

## Purpose
Constructor round-trip coverage for every transport-neutral outbound type. There is no behaviour to
test here; the spec exists so that adding, renaming, or removing a field on a sealed variant is a
conscious change that touches a test.

## Key Files
| File | Description |
|------|-------------|
| `OutboundMessageTest.kt` | `BehaviorSpec` that constructs and reads back: the `@JvmInline` value classes `ConversationTarget`, `UserRef`, `ModalOpenHandle`, `ResponseReplaceHandle` and the `MessageRef` pair; every `MessageContent` variant (`Text`, `ErrorNotice`, `Schedule` with `TimeScheduleInfo`, `Form` with `SelectionContents` / `TextInputContents` / `ApprovalContents`, `MeetingRequest`); every `ModalForm` variant (`Reschedule`, `AddParticipant`, `StandupFill`, `StandupSetup`, `DeclineReason`); every `OutboundMessage` variant (`ChannelMessage`, `Ephemeral` — `recipient == null` means the publisher, `DirectMessage`, `UpdateMessage`, `ReplaceMessage`, `OpenModal`, `Approval` — `routingExtras` defaults to empty, `Notice`). Builds `ApprovalContents` through a private `approvalContents()` helper instead of the fixture `createApprovalContents` |

## For AI Agents

### Working In This Directory
- When you add a variant to `OutboundMessage`, `MessageContent`, or `ModalForm`, add a construction
  block for it here in the matching `given`. The `when` over the sealed hierarchy in infrastructure's
  resolver is the other place that must change.
- Prefer the fixture `createApprovalContents` over the local helper if this file is touched.

### Testing Requirements
```bash
./gradlew :domain:test --tests 'dev.notypie.domain.command.outbound.OutboundMessageTest'
```

### Common Patterns
- One `then("fields round-trip")` per sealed family, constructing each variant with named arguments
  and asserting every property back.

## Dependencies

### Internal
- `dev.notypie.domain.command.outbound.*`, `command.dto.modals.{ApprovalContents, SelectionContents,
  TextInputContents, TimeScheduleInfo}`, `command.entity.CommandDetailType`.

### External
- Kotest (`BehaviorSpec`, `shouldBe`), `java.time.LocalDateTime`, `java.util.UUID`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
