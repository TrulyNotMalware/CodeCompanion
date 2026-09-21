<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# domain/command/entity/context/form

## Purpose
The button- and modal-driven contexts. Each user flow is two contexts: a `*Context` that reacts to a
button (it has a live trigger handle and opens a modal) and a `*SubmissionContext` that reacts to the
modal's `view_submission` (it reads a typed `InboundSubmission` and queues an intent). All extend
`ReactionContext`.

## Key Files
| File | Description |
|------|-------------|
| `RequestMeetingContext.kt` | `/meetup` and the meeting modal (`MEETING_CREATE_REQUEST`). `runCommand`: `LIST` → `MeetingListRange` parse → `CommandIntent.MeetingListRequest`, else `MessageContent.MeetingRequest`. `handleInteraction`: Deny → "Meeting request canceled."; else `MeetingFormInput.from(...)`, four form checks, `toMeeting()` (domain validation rendered as `field: reason` lines), optional notice fan-out via `ApprovalCallbackContext`, returns `RequestMeetingContextResult` |
| `MeetingFormInput.kt` | `internal data class` decoded from the meeting modal: participants = `USERS` field minus publisher; `startAt` = `DATE` + first `TIME` (must be in the future); `endAt` = second `TIME`; title / reason = first two `TEXT` fields with defaults "New Meeting" / "request meeting"; `noticeRequired` = `TOGGLE`; `toMeeting()` |
| `ApprovalCallbackContext.kt` | Fans out one `OutboundMessage.Approval` per participant (`APPROVAL_CALLBACK`); aggregates a `CommandOutput` from the per-participant results |
| `MeetingApprovalResponseContext.kt` | Accept / Deny buttons on the notice (`MEETING_APPROVAL_REQUEST`). `APPROVE` → `MeetingAttendanceUpdate(isAttending = true)` + "You accepted" reply; `REJECT` → `OpenModal(DeclineReason)` first, then a provisional `MeetingAttendanceUpdate(OTHER)`, no reply |
| `DeclineReasonSubmissionContext.kt` | Decline modal submit (`MEETING_DECLINE_REASON`): parses `RejectReason` (unknown / `ATTENDING` → `OTHER`), detail kept only for `OTHER`, queues the final `MeetingAttendanceUpdate` and an `UpdateMessage` on the original notice |
| `CancelMeetingContext.kt` | Cancel button on `/meetup list` (`CANCEL_MEETING`): `routingExtras[0]` → `CommandIntent.CancelMeeting` |
| `RescheduleMeetingContext.kt` / `RescheduleMeetingSubmissionContext.kt` | Reschedule button → `OpenModal(Reschedule)`; submit parses `date time` with `yyyy-MM-dd HH:mm` → `CommandIntent.RescheduleMeeting` |
| `AddParticipantContext.kt` / `AddParticipantSubmissionContext.kt` | Add-participant button → `OpenModal(AddParticipant)`; submit splits the comma-joined user ids → `CommandIntent.AddParticipant` |
| `RequestStandupSetupContext.kt` / `StandupSetupSubmissionContext.kt` | `/standup setup` → `OpenModal(StandupSetup)`; submit parses name, `\n`-separated questions, comma-separated members and weekdays, `LocalTime`, cutoff minutes (default 120), `ZoneId` (default `Asia/Seoul`) → `CommandIntent.CreateStandupRoutine` |
| `StandupFillContext.kt` / `StandupAnswerSubmissionContext.kt` | "Fill in standup" button (`routingExtras = [sessionUid, routineUid]`) → `OpenModal(StandupFill)` with the origin notice; submit → `CommandIntent.RecordStandupAnswer` (only if answers non-empty) + `UpdateMessage("Standup submitted.")` |
| `CveSubscriptionRequestContexts.kt` | `RequestCveSubscribeContext` / `RequestCveUnsubscribeContext` open the topic modals; `RequestCveSubscriptionsContext` queues `CveListSubscriptions` |
| `CveSubscriptionSubmissionContexts.kt` | `CveSubscribeSubmissionContext` / `CveUnsubscribeSubmissionContext` → intents keyed on `interaction.actor.id` |
| `RequestCveLatestContext.kt` | `/latest [topic-key]` → `CommandIntent.CveLatest` (`CVE_LATEST`) |

## For AI Agents

### Working In This Directory
- A `view_submission` carries no channel and no reply handle. Submission contexts therefore never call
  `interactionSuccessResponse`; the modal closing on `200 OK` is the acknowledgement, and the only way
  to touch the originating message is `OutboundMessage.UpdateMessage` with a `MessageRef` that the
  request context ferried through the modal (`ModalForm.*.originNotice` / `channel`).
- Malformed routing is a silent no-op by design: a bad UUID in `routingExtras`, a `submission` of the
  wrong type, a blank date/time, an empty selection — all return `CommandOutput.success` with nothing
  queued. Repositories defend the real invariants (host-only `WHERE`, capacity). Do not turn these into
  errors without changing the modal UX to match.
- Effect order is load-bearing: `MeetingApprovalResponseContext.handleDecline` queues `OpenModal`
  before the provisional intent so `views.open` fires within the trigger's ~3 s window while the
  attendance write happens at `BEFORE_COMMIT`. Keep `OpenModal` first in any button handler.
- `requesterId` / `userId` / `creatorId` fall back to `interaction.actor.id` when the ferried value is
  blank; the actor is always the authority for "who clicked".
- `StandupSetupSubmissionContext` does not validate. `Routine`'s `init` block is the single source of
  truth and runs when `application/service/standup/StandupRoutineSetupService` builds the entity;
  invalid weekday tokens are dropped and unparsable time / cutoff / timezone fall back to defaults.
- `RequestMeetingContext` renders `CodeCompanionRuntimeException.details` from `Meeting` as
  `fieldName: reason` lines in an ephemeral — wording changes to `Meeting` invariants show up in
  `MeetingContextTest`.
- `MeetingFormInput` reads the meeting modal positionally by field kind (first / second `TIME`, first
  two `TEXT`), unlike the other flows which use `InboundFieldKeys`. Reordering blocks in that modal
  template breaks parsing silently.
- `ApprovalCallbackContext.sendNoticeToParticipants` returns `success` per participant and `all {}`
  over an empty list is `true`, so `RequestMeetingContext.sendNotice` cannot currently return `false`;
  the "Failed to send notice" branch is unreachable until the fan-out reports real failures.
- Routing token layout (`<idempotencyKey>,<DETAIL_TYPE>,<extra...>`) is documented in
  `command/inbound/AGENTS.md`; each context's KDoc states which extras it reads.

### Testing Requirements
```bash
./gradlew :domain:test --tests 'dev.notypie.domain.command.context.*'
```
Specs (all under `domain/src/test/kotlin/dev/notypie/domain/command/context/`): `MeetingContextTest`
(for `RequestMeetingContext` + `MeetingFormInput`), `ApprovalCallbackContextTest`,
`MeetingApprovalResponseContextTest`, `DeclineReasonSubmissionContextTest`,
`RescheduleMeetingContextTest`, `RescheduleMeetingSubmissionContextTest`, `AddParticipantContextTest`,
`AddParticipantSubmissionContextTest`, `StandupFillContextTest`, `StandupAnswerSubmissionContextTest`,
`StandupSetupSubmissionContextTest`, `CveSubscriptionSubmissionContextTest`. Not covered:
`CancelMeetingContext`, `RequestStandupSetupContext`, `RequestCveLatestContext`, the three
`RequestCve*Context`s — add a spec when touching them. Build interactions with
`InboundInteractionInputCreator` (`testFixtures`), extend `AbstractReactionCommandContextTest`.

### Common Patterns
```kotlin
override fun handleInteraction(interaction: InboundInteraction): CommandOutput {
    val s = interaction.submission as? InboundSubmission.Xxx ?: return successOutput()
    val uid = runCatching { UUID.fromString(s.uidRaw) }.getOrElse { return successOutput() }
    addIntent(CommandIntent.Xxx(...))
    return successOutput()
}
```
- `private fun successOutput()` per context wrapping the three-argument `CommandOutput.success`.
- Modal-open contexts read `interaction.trigger.raw` into `ModalOpenHandle` and `interaction.channelId`
  into `ConversationTarget` for the form's private metadata.

## Dependencies

### Internal
- `command/entity/context` (`ReactionContext`), `command/entity/slash` (definitions, `MeetingListRange`,
  `RequestMeetingContextResult`), `command/dto`, `command/dto/modals`, `command/dto/response`,
  `command/inbound`, `command/intent`, `command/outbound`, `common/error`, `meet/entity` (`Meeting`,
  `RejectReason`)

### External
`java.time`, `java.util.UUID` only.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
