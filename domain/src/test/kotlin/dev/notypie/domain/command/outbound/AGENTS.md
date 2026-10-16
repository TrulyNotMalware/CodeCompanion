<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-19 -->

# domain/command/outbound (test)

## Purpose
Constructor round-trip coverage for every transport-neutral outbound type, so that adding, renaming, or
removing a field on a sealed variant is a conscious change that touches a test. The one behaviour pinned
here is the redacted `toString()` of `ResponseReplaceHandle` in every data class that carries it.

## Key Files
| File | Description |
|------|-------------|
| `OutboundMessageTest.kt` | `BehaviorSpec` that constructs and reads back: the `@JvmInline` value classes `ConversationTarget`, `UserRef`, `ModalOpenHandle`, `ResponseReplaceHandle` and the `MessageRef` pair; every `MessageContent` variant (`Text`, `ErrorNotice`, `Schedule` with `TimeScheduleInfo`, `MeetingRequest` with `ApprovalContents`); every `ModalForm` variant (`Reschedule`, `AddParticipant`, `StandupFill`, `StandupSetup`, `DeclineReason`); every `OutboundMessage` variant (`ChannelMessage`, `Ephemeral` — `recipient == null` means the publisher, `DirectMessage`, `UpdateMessage`, `ReplaceMessage`, `OpenModal`, `Approval` — `routingExtras` defaults to empty, `Notice`; `ChannelMessage` / `Ephemeral` `fallback` defaults to `null`). A `ResponseReplaceHandle` over `TEST_SLACK_RESPONSE_URL` prints as `ResponseReplaceHandle(redacted)` (directly, in a template and boxed inside a `List`), and no carrier prints `hooks.slack.com` — the non-null `ReplaceMessage.handle` and the nullable `listHandle` of `ModalForm.Reschedule` / `AddParticipant`, `CommandIntent.CancelMeeting` / `RescheduleMeeting` / `AddParticipant`, `AddParticipantParsed`, `RescheduleMeetingParsed`. The exact wording of `channelRefusedNotice` and `directFallbackOf` (prefix line, original headline kept, no transport name) is pinned here, and a hostile channel id (`C1> <https://evil.example|Re-authorize & go`) comes out escaped in both. Builds `ApprovalContents` through a private `approvalContents()` helper instead of the fixture `createApprovalContents` |

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
- `dev.notypie.domain.command.outbound.*`, `command.dto.modals.{ApprovalContents, TimeScheduleInfo}`, `command.entity.CommandDetailType`.

### External
- Kotest (`BehaviorSpec`, `shouldBe`), `java.time.LocalDateTime`, `java.util.UUID`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
