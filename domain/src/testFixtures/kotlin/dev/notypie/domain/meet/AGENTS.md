<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# domain/src/testFixtures/kotlin/dev/notypie/domain/meet

## Purpose
Builders for the meeting lane: the `Meeting` aggregate, its read-side DTOs (`MeetingDto`,
`MeetingParticipantDto`, `MeetingReminderDto`) and the four meeting request events
(`UpdateMeetingAttendance`, `CancelMeeting`, `GetMeetingList`, `RescheduleMeeting`).

## Key Files
| File | Description |
|------|-------------|
| `MeetingTestFixtures.kt` | `createMeeting(title = "Standup", publisher = "U001", members = {U002, U003}, reason, startAt = now+1d, endAt = startAt+1h, isCanceled, meetingUid)`; `createUpdateMeetingAttendanceEvent(meetingIdempotencyKey, participantUserId, isAttending = false, absentReason = OTHER, idempotencyKey)`; `createCancelMeetingEvent(meetingUid, requesterId, idempotencyKey, responseBasicInfo)`; `createGetMeetingListEvent(publisherId, startDate = now, endDate = now+1w, ...)`; `createRescheduleMeetingEvent(meetingUid, requesterId, newStartAt = now+1d, ...)` |
| `MeetingDtoCreator.kt` | `createMeetingDto(meetingId = 0L, meetingUid, idempotencyKey, creator = TEST_USER_ID, title = "Test Meeting", reason, startAt = now+1d, endAt = startAt+1h, participants = [], isCanceled)`; `createMeetingParticipantDto(userId (required), isAttending = true, absentReason = ATTENDING, absentReasonDetail)` |
| `MeetingReminderDtoCreator.kt` | `createMeetingReminderDto(id = 1L, meetingId = 1L, offsetMinutes = 15, scheduledAt = 2026-05-01T01:00Z, sentAt, status = PENDING, failureReason)` |

## For AI Agents

### Working In This Directory
- Consumers by builder: `createMeeting` → domain `meet/entity/MeetingTest`; `createMeetingDto` →
  application `MeetingServiceImplTest`, `MeetingRescheduleServiceTest`, `mcp/DomainReadToolsTest`,
  infrastructure `SlackOutboundRendererTest`, `OutboundMessageCodecTest`, `ModalTemplateBuilderTest`;
  `createMeetingParticipantDto` → `MeetingRescheduleServiceTest`, `ModalTemplateBuilderTest`;
  `createMeetingReminderDto` → `MeetingReminderSchedulingServiceTest`; the events →
  `MeetingServiceImplTest` (attendance, cancel, list) and `MeetingRescheduleServiceTest` (reschedule).
- Time defaults are `LocalDateTime.now()`-relative in `createMeeting`, `createMeetingDto`,
  `createGetMeetingListEvent` and `createRescheduleMeetingEvent`. Pass explicit values whenever a spec
  renders or serialises the time (codec, renderer and modal specs), or the assertion is flaky by
  construction.
- `createMeeting` defaults `publisher` to `"U001"` rather than `TEST_USER_ID`; specs that compare host ids
  against a `TEST_USER_ID` actor must set it.
- The events here embed `responseBasicInfo` from `../command/createCommandBasicInfo` with the same
  `idempotencyKey`, like the command-package creators; `createUpdateMeetingAttendanceEvent` is the one
  event without basic info because its payload has none.

### Testing Requirements
No spec for the fixtures themselves; `MeetingTest` and the service/renderer specs above are the coverage.
Add a builder parameter here when `MeetingDto` or `Meeting` gains a field — infrastructure and application
both compile against this file.

### Common Patterns
- `endAt` defaults derived from `startAt` inside the parameter list, so overriding the start keeps the
  duration.
- One creator per event type, named `create<EventClass>`.

## Dependencies

### Internal
- `domain/meet/entity/` (`Meeting`, `RejectReason`, `enums/MeetingReminderStatus`), `domain/meet/dto/`
- `domain/command/entity/event/` meeting events, `domain/command/dto/CommandBasicInfo`
- `../command/createCommandBasicInfo`, `../Constants.kt`

### External
- `java.time`, `java.util.UUID`

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
