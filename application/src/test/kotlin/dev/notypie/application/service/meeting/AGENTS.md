<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# test/kotlin/dev/notypie/application/service/meeting

## Purpose
Specs for the meeting lane: the daily agenda DM scheduler and its message builder, the reminder
materialize/send scheduler, the reschedule listener, and the `MeetingServiceImpl` event listeners
(attendance update, cancel, decline-modal fallback, add participants, meeting list). Effects are asserted on
`OutboundMessage` values captured at `OutboundMessagePort.toRow` or `OutboundMessageStager.stage`.

## Key Files
| File | Description |
|------|-------------|
| `DailyAgendaMessageBuilderTest.kt` | Plain Kotest `BehaviorSpec`, no mocks. `buildAgendaDm` with two items supplied out of order → headline `🗓️ Today's meetings (2026-05-04)`, markdown `• 10:00 — Sprint Planning\n• 14:00 — 1:1 with Lead` (sorted by start), `DAILY_AGENDA`, target = command channel. |
| `DailyAgendaSchedulingServiceTest.kt` | Plain Kotest `BehaviorSpec` + MockK; `AppConfig.Meeting.Agenda(sendAt = "08:00", timezone = "Asia/Seoul")`. 07:00 Seoul → no `claim`, no `findAttendingMeetingsForDay`, no save; disabled → same; 12:00 with two meetings (`U_A` in both, `U_B` in one) → `claim` once, two saves, `publisherId`s `{U_A, U_B}`, each `ChannelMessage` `DAILY_AGENDA` targeted at the user with only that user's lines; claim lost → no load, no save; claim won but empty day → no save; "canceled meeting" case → repository stubbed empty, no save. |
| `MeetingReminderSchedulingServiceTest.kt` | Plain Kotest `BehaviorSpec` + MockK; offsets `[15, 5]`, clock 12:00 Seoul. `materializeReminders`: meeting in 10 min with attendees → `ensureReminder(7L, 15, …)` and `(7L, 5, …)` once each; no attendees → none; `DataIntegrityViolationException` + `reminderExists` true → swallowed, `reminderExists` checked per offset; DIVE + false → propagates. `sendDueReminders`: claim won with two attendees → `claimReminder` once, two saves, `markReminderSent` once with the claim token, exact `MEETING_REMINDER` `ChannelMessage` per attendee (`Your meeting *Sprint Planning* starts in 15 minutes (at 2026-05-04 12:15).`); `markReminderSent` false → `markReminderFailed` once; claim lost → nothing; `toRow` throws → `markReminderFailed(reason contains "Slack API error")`, no save; nothing due → no claim; `resetStuckReminders` returns 3 → called once. "a canceled meeting" and "a reminder DTO domain invariant" are covered below. |
| `MeetingRescheduleServiceTest.kt` | Plain Kotest `BehaviorSpec` + MockK. `rescheduleMeeting` true → `reminderRepository.deleteByMeetingId(42L)`, `ChannelMessage` `Meeting rescheduled` / `[Notice] <@U_P1> <@U_P2> *Test Meeting* has been rescheduled to 2026-07-01 14:30.` typed `MEETING_RESCHEDULE_SUBMIT`, plus `Ephemeral` `Meeting rescheduled to 2026-07-01 14:30.` to the requester; false → `Meeting was canceled, or you are not the host.`, no delete, no `findMeetingByUid`, no channel message; throws → `Failed to reschedule the meeting. Please try again later.`, no delete. |
| `MeetingServiceImplTest.kt` | Plain Kotest `BehaviorSpec` + MockK; `retryService.execute` is a passthrough (`firstArg<() -> Int>().invoke()`). `updateParticipantAttendance`: 1 row → `participantExists` never; 0 rows + exists → swallowed; 0 rows + missing → `IllegalStateException`. `cancelMeeting`: true → `Ephemeral` `Meeting canceled.` (`CANCEL_MEETING`, recipient = requester) and the published queue holds exactly the staged event; false → `Meeting was already canceled, or you are not the host.`; throws → `Failed to cancel the meeting. Please try again later.`. `onDeclineModalOpenFailed` → one ephemeral published, no `UpdateMeetingAttendanceEvent` re-published. `addParticipants`: `ADDED` → `Approval` `MEETING_APPROVAL_REQUEST` to `U_A` and `U_B` plus host ephemeral `Added <@U_A> <@U_B> to the meeting.`; `NOT_AUTHORIZED` → zero approvals, `Meeting was canceled, or you are not the host.`. `getMeetingListEvent`: → `Ephemeral(MessageContent.MeetingList(meetings, currentUserId))` published; throws → `ERROR_RESPONSE` `Failed to fetch your meetings. Please try again later.`. |

## For AI Agents

### Working In This Directory
- Two cases are tautological: the "canceled meeting" cases in both scheduler specs stub the window query to
  return an empty list, so they document that filtering lives in the repository rather than exercising it.
  The "reminder DTO domain invariant" case only asserts `createMeetingReminderDto(status = PENDING).status`,
  a fixture self-check. Treat all three as placeholders when extending coverage.
- `MeetingRescheduleServiceTest` and `MeetingServiceImplTest` share spec-scope mocks across `when` blocks;
  Kotest accumulates `verify` counts, so later `when`s either build `local*` mocks (reschedule) or call
  `clearMocks(stager)` first (add participants). Keep that discipline when adding cases.
- Scheduler clocks are `Clock.fixed(12:00 Asia/Seoul, seoul)`; the agenda spec has a second
  `beforeSendInstant` at 07:00. `readyReminderOf` derives `startAt` from `nowInstant + offsetMinutes`, which
  is why the reminder text says `12:15` for the 15-minute offset.
- Claim-token CAS is asserted with two slots (`claimReminder` vs `markReminderSent`) compared for equality;
  `stubPort()`/`stubTransactionManager()` are file-local copies, as in `standup`.
- `buildAgendaDm` is an `internal fun` in `DailyAgendaSchedulingService.kt`; `AgendaItem` is its own file.
- `MeetingServiceImplTest` stubs `retryService.execute<Int>(action = any(), any(), …)` with eight positional
  `any()`s; a signature change on `RetryService.execute` breaks the stub before any case runs.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.service.meeting.*'
```
Fixtures used: `application` testFixtures `service/meeting/AgendaItemCreator.kt` (`createAgendaItem`,
`createAgendaCandidateMeeting`, `createReminderCandidateMeeting`) and `outbox/OutboxTestFixtures.kt`
(`createOutboxRow`); `domain` testFixtures `command/CommandDomainInputCreator.kt` (`createCommandBasicInfo`),
`meet/MeetingDtoCreator.kt` (`createMeetingDto`, `createMeetingParticipantDto`), `meet/MeetingTestFixtures.kt`
(`createRescheduleMeetingEvent`, `createCancelMeetingEvent`, `createGetMeetingListEvent`,
`createUpdateMeetingAttendanceEvent`), `meet/MeetingReminderDtoCreator.kt` (`createMeetingReminderDto`);
`infrastructure` testFixtures `impl/command/event/SlackEventTestFixtures.kt` (`createSendSlackMessageEvent`).
`ReadyReminder` is built inline through `readyReminderOf`; the domain entity builder `createMeeting` is unused.

### Common Patterns
- Exact-message assertions use equality on a fully spelled `OutboundMessage.Ephemeral(...)` /
  `ChannelMessage(...)` with `basicInfo = basic`; branch-only checks use `match { it is Approval && … }`.
- Agenda DMs are correlated user → message by zipping `capturedInfos.map { it.publisherId }` with
  `capturedMessages`, both captured through `mutableListOf` on `port.toRow`.

## Dependencies

### Internal
- `application/service/meeting/DailyAgendaSchedulingService.kt`, `AgendaItem.kt`,
  `MeetingReminderSchedulingService.kt`, `MeetingRescheduleService.kt`, `MeetingServiceImpl.kt`,
  `application/service/command/CommandExecutor`, `application/configurations/AppConfig.Meeting`
- `domain/command/entity/event/AddParticipantEvent`, `DeclineModalOpenFailedEvent`,
  `UpdateMeetingAttendanceEvent`, `domain/meet/entity/RejectReason`, `domain/command/outbound/*`
- `infrastructure/repository/meeting/AgendaDispatchRepository`, `MeetingReminderRepository`, `ReadyReminder`,
  `MeetingRepository`, `AddParticipantResult`, `repository/outbox/MessageOutboxRepository`,
  `OutboundMessagePort`, `impl/retry/RetryService`

### External
MockK (`clearMocks`, `slot`), Kotest, Spring `PlatformTransactionManager`, `DataIntegrityViolationException`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
