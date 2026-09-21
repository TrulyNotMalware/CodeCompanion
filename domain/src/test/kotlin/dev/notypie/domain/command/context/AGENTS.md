<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-09-21 -->

# domain/command/context (test)

## Purpose
One spec per `CommandContext` — the unit that turns a parsed command or interaction into
`CommandIntent`s and `OutboundMessage`s on an `IntentQueue`. Covers both main packages
`command/entity/context/` (plain contexts) and `command/entity/context/form/` (modal-backed contexts,
shown as `form.` below). Every spec constructs the context directly, feeds it a fixture-built input,
and asserts on the queue; no transport, no Slack payloads.

## Key Files
All specs are Kotest `BehaviorSpec`s.

| File | Description |
|------|-------------|
| `AbstractCommandContextTest.kt` | Two spec classes (`AbstractCommandContextTest`, `AbstractReactionCommandContextTest`) that pin the base contracts via anonymous subclasses: default `runCommand()` returns `CommandOutput.empty()`, `runCommand` stays `open` (guards against dropping the keyword), `ReactionContext.interactionSuccessResponse` enqueues a `ReplaceMessage` on the reply handle and returns either the default success or the supplied `results`. It is a contract test, not a base class — nothing extends it |
| `AddParticipantContextTest.kt` | `form.AddParticipantContext`: "Add participant" click → `OpenModal(ModalForm.AddParticipant)` with trigger, `meetingUid` from `routingExtras[0]`, requester, channel; malformed uid → success with no effects |
| `AgentChatContextTest.kt` | `AgentChatContext`: `PIPELINE` / `AGENT_CONVERSE`; `CommandIntent.AgentConverse` carries prompt, `threadId` (nullable), requester and channel names; blank prompt → failed output plus an `Ephemeral` with `EMPTY_PROMPT_MESSAGE` |
| `ApprovalCallbackContextTest.kt` | `form.ApprovalCallbackContext`: one `OutboundMessage.Approval` per participant, empty set is a vacuous success, custom `ApprovalContents` (via `createApprovalContents`) vs the default |
| `ApprovalFormContextTest.kt` | `ApprovalFormContext`: `PIPELINE` / `APPROVAL_REQUEST`; single `ChannelMessage` whose content is a `MessageContent.Form` headlined "Approve Form" |
| `DetailErrorAlertContextTest.kt` | `DetailErrorAlertContext`: `ChannelMessage` with `MessageContent.ErrorNotice` (`className`, `message`, nullable `details`); built from `createMentionInboundCommand` |
| `EmptyContextTest.kt` | `EmptyContext`: `SIMPLE` / `NOTHING`, `CommandOutput.empty()` (checked with `dto.isEmpty()`), no effects |
| `EphemeralTextContextTest.kt` | `EphemeralTextResponseContext`: `Ephemeral` to the publisher (`recipient == null`) in the command channel; `isOk = false` yields `FAILED` but still emits |
| `MeetingApprovalResponseContextTest.kt` | `form.MeetingApprovalResponseContext`: APPROVE → `MeetingAttendanceUpdate(true, ATTENDING)` + `ReplaceMessage` "You accepted the meeting invitation."; DECLINE → `OpenModal(ModalForm.DeclineReason)` first (title from `routingExtras[0]`, origin notice from `message`), a provisional `MeetingAttendanceUpdate(OTHER)`, and **no** `ReplaceMessage`; regression case proving a button-only payload never triggers "Select participants". Uses `applyButtonField`, `rejectButtonField` |
| `MeetingContextTest.kt` | `form.RequestMeetingContext`, the largest spec. `NONE` → `ChannelMessage(MeetingRequest)`; `LIST` with no / blank / each valid / unknown / too-many options → `MeetingListRequest` window per `MeetingListRange` or an `Ephemeral` usage hint (`recipient` must stay `null`); form submission: happy path, explicit end time, same start/end ("End time must be after start time."), no end time (entity default), reject button ("Meeting request canceled."), no participants ("Select participants"), over-long title (message rendered from the `Meeting` entity's own validation). Uses `plainTextField`, `datePickerField`, `timePickerField`, `multiUsersField`, `MeetingFormInput.DATE_PATTERN` / `SIMPLE_TIME_PATTERN` |
| `ParsedSubmissionsTest.kt` | The per-variant `*Parsed.from` factories (Phase 11): uid/date/time rejection, actor fallback for blank routing tokens, `RejectReason` coercion, blank-OTHER detail surviving as `""`, `NoticeTarget.of` partial routing → `None`, standup-setup defaults, CVE parsers never rejecting |
| `NoticeContextTest.kt` | `NoticeContext`: `OutboundMessage.Notice` with `UserRef` mentions and space-joined command text; empty inputs still succeed |
| `ReplaceMessageContextTest.kt` | `ReplaceMessageContext`: `SIMPLE` / `REPLACE_TEXT`; both `runCommand` and `handleInteraction` enqueue a `ReplaceMessage` on the reply handle |
| `RequestApprovalContextTest.kt` | `RequestApprovalContext`: `PIPELINE` / `APPLY_REQUEST`; one `Approval` targeted at the channel, reason taken from the commands queue. Constructor parameter is `basicInfo`, not `commandBasicInfo` |
| `RescheduleMeetingContextTest.kt` | `form.RescheduleMeetingContext`: click → `OpenModal(ModalForm.Reschedule)`; malformed uid → no effects |
| `StandupFillContextTest.kt` | `form.StandupFillContext`: Fill click with `routingExtras = [sessionUid, routineUid]` → `OpenModal(ModalForm.StandupFill)` carrying the origin notice ref; malformed extras → no intents |
| `SubmissionContextsTest.kt` | All seven `Submission` leaves plus `IgnoredSubmissionContext`: each holds an already-parsed model and `accept` translates it into effects (persistence before UI for decline, standup empty-answer notice update, empty CVE key lists); Ignored returns success with an empty queue |
| `TextResponseContextTest.kt` | `TextResponseContext`: `ChannelMessage(Text)` in the command channel with headline "Simple Text Response" |

## For AI Agents

### Working In This Directory
- Every new `CommandContext` in main gets a spec here, named `<Context>Test.kt`, in this package
  regardless of whether main puts it in `context/` or `context/form/`.
- Honour the graceful-degradation contract the modal flows share: malformed routing data or uuid
  → `result.ok == true` **and** an empty queue. Slack must still get a 200 so the modal closes; the
  absence of intents is the signal. Since Phase 11 rejection happens in the `*Parsed.from` factories
  (`ParsedSubmissionsTest`) and routing to `IgnoredSubmissionContext` in `../parsers/SubmissionRouterTest`;
  leaves no longer see malformed input.
- When persistence and UI effects are both emitted, assert their order with `indexOfFirst` as
  `SubmissionContextsTest` (decline) and `MeetingApprovalResponseContextTest` do — the resolver
  processes the queue in order and the tests are the only place that order is written down.
- Constructor signatures differ between contexts (`commandBasicInfo` vs `basicInfo`, extra ctor args
  such as `prompt`, `replyHandle`, `participants`); check the class before copying a sibling spec.
- The user-facing strings asserted here are the contract; change them in main and here together.

### Testing Requirements
```bash
./gradlew :domain:test --tests 'dev.notypie.domain.command.context.*'
./gradlew :domain:test --tests 'dev.notypie.domain.command.context.MeetingContextTest'
```

### Common Patterns
- Setup: `createIntentQueue()` per `given`, `createCommandBasicInfo()`, then either `runCommand()` or
  `handleInteraction(interaction = createInboundInteraction(detailType = ..., action = approveAction(),
  submission = InboundSubmission.X(...)))`.
- Assertion: `intentQueue.snapshot()` (non-destructive) or `drainSnapshot()` (clears), then
  `filterIsInstance<CommandIntent.X>().single()` or `shouldBeInstanceOf<OutboundMessage.X>()`.
- Metadata checks (`context.commandType`, `context.commandDetailType`) sit in their own `when`.
- Fixtures used: `createCommandBasicInfo`, `createIntentQueue`, `createInboundInteraction`,
  `approveAction`, `rejectAction`, `applyButtonField`, `rejectButtonField`, `plainTextField`,
  `datePickerField`, `timePickerField`, `multiUsersField`, `createApprovalContents`,
  `createMentionInboundCommand`, `dto.isEmpty`, and the `TEST_*` constants.

## Dependencies

### Internal
- `dev.notypie.domain.command.entity.context.*` and `.form.*` (classes under test);
  `command.intent.CommandIntent`; `command.outbound.{OutboundMessage, MessageContent, ModalForm, UserRef}`;
  `command.inbound.{InboundSubmission, InboundActor, TriggerHandle, MessageHandle}`;
  `command.entity.slash.{MeetingListRange, MeetingSubCommandDefinition}`; `meet.entity.{Meeting, RejectReason}`.
- `testFixtures` — `command/*Creator.kt`, `dto/Utils.kt`, `Constants.kt`.

### External
- Kotest (`BehaviorSpec`, core + collections + types matchers), `java.time`, `java.util.UUID`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
