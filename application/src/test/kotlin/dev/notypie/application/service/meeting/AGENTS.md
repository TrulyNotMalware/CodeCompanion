<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-09-30 -->

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
| `DailyAgendaSchedulingServiceTest.kt` | Plain Kotest `BehaviorSpec` + MockK; `AppConfig.Meeting.Agenda(sendAt = "08:00", timezone = "Asia/Seoul")`. 07:00 Seoul → no `claim`, no `findAttendingMeetingsForDay`, no save; disabled → same; 12:00 with two meetings (`U_A` in both, `U_B` in one) → `claim` once, two saves, `publisherId`s `{U_A, U_B}`, each `ChannelMessage` `DAILY_AGENDA` targeted at the user with only that user's lines; claim lost → no load, no save; claim won but empty day → no save; outbox write throws → `setRollbackOnly` after `claim` and `save` (mock transaction manager). Real-transaction pair on `createH2TransactionManager`: the stubbed `claim` inserts into an H2 `agenda_claim` table through `JdbcTemplate`; a failing outbox write leaves 0 rows, a succeeding one 1 — this breaks if claim and outbox writes ever run in separate transactions. The `isCanceled` filter itself is covered by `JpaMeetingRepositoryTest` in `:infrastructure`. |
| `MeetingReminderSchedulingServiceTest.kt` | Plain Kotest `BehaviorSpec` + MockK; offsets `[15, 5]`, clock 12:00 Seoul. `materializeReminders`: meeting in 10 min with attendees → `ensureReminder(7L, 15, …)` and `(7L, 5, …)` once each; no attendees → none; `DataIntegrityViolationException` + `reminderExists` true → swallowed, `reminderExists` checked per offset; DIVE + false → propagates. `sendDueReminders`: claim won with two attendees → `claimReminder` once, two saves, `markReminderSent` once with the claim token, exact `MEETING_REMINDER` `ChannelMessage` per attendee (`Your meeting *Sprint Planning* starts in 15 minutes (at 2026-05-04 12:15).`); `markReminderSent` false → `markReminderFailed` once; claim lost → nothing; `toRow` throws → `markReminderFailed(reason contains "Slack API error")`, no save; nothing due → no claim; `resetStuckReminders` returns 3 → called once. |
| `MeetingRescheduleServiceTest.kt` | Plain Kotest `BehaviorSpec` + MockK; `createFixedClock(2026-07-01T14:29:40)`. `rescheduleMeeting` `Rescheduled(meeting)` → `reminderRepository.deleteByMeetingId(42L)`, `ChannelMessage` `Meeting rescheduled` / `[Notice] <@U_P1> <@U_P2> *Test Meeting* has been rescheduled to 2026-07-01 14:30.` typed `MEETING_RESCHEDULE_SUBMIT`, plus `Ephemeral` `Meeting rescheduled to 2026-07-01 14:30.` to the requester; `NotAuthorized` → `Meeting was canceled, or you are not the host.`, no delete, no channel message; `AlreadyAtRequestedTime` → exactly one stage, the neutral `The meeting is already scheduled for 2026-07-01 14:30. Nothing was changed.` to the requester, no delete; 09:00 (past) and 14:29 (the clock's current minute) → `Pick a future time. The meeting was not rescheduled.` to the requester and the repository is never called; first attempt conflicts inside the H2-backed outer transaction and the retry succeeds → nothing escapes, only `Meeting rescheduled to 2026-07-01 14:30.` is committed, one delete; throws `RuntimeException` → one attempt only, `Failed to reschedule the meeting. Please try again later.`, no delete. |
| `MeetingWriteJpaTransactionTest.kt` | Plain `BehaviorSpec`, no Spring context: `createH2MeetingJpaStore()` gives a Hibernate `EntityManagerFactory` over H2 (meeting schema package, `create-drop`), a `JpaTransactionManager`, proxied Spring Data repositories with exception translation, and the real `MeetingRepositoryImpl` / `MeetingReminderRepositoryImpl`. Each interaction runs inside an outer `TransactionTemplate` like `handleInteraction`. Resubmits: reschedule → first commits the move, one channel notice, drops the armed reminder, version 1; the resubmit commits only `The meeting is already scheduled for 2026-07-02 10:00. Nothing was changed.`, keeps the re-armed reminder, version 1; cancel → `Meeting canceled.` then `Meeting was already canceled, or you are not the host.`, version 1; add → one `Approval` then none and `Those people are already on this meeting.`, version 1. Fresh state: a `MeetingRepository` delegate loads the meeting into the first attempt's persistence context, commits a competing add in a `REQUIRES_NEW` transaction, then calls the real write → the first attempt conflicts, the retry runs in a second `EntityManager` (both differ from the outer one), applies the reschedule on top of the add (version 2) and the channel notice mentions the competitor. V21 race: a cancel loads a meeting whose `end_at` is before its start, the real `V21__fix_inverted_meeting_end_at.sql` statement (read from the classpath, comments stripped) runs in a `REQUIRES_NEW` transaction, then the cancel flushes → the repair's `version` bump makes the first attempt conflict, the retry cancels the repaired row: `end_at` stays `NULL`, version 2 |
| `MeetingServiceImplTest.kt` | Plain Kotest `BehaviorSpec` + MockK. `updateParticipantAttendance`: 1 row → `participantExists` never; 0 rows + exists → swallowed; 0 rows + missing → `IllegalStateException`; the UPDATE throws (a `Data too long` `DataIntegrityViolationException`) → that same exception propagates after exactly one repository call. `createNewMeeting` (`createRequestMeetingContextResult`): the INSERT throws → the same exception after exactly one call (review T22: no retry inside the caller's doomed transaction). `cancelMeeting`: true → `Ephemeral` `Meeting canceled.` (`CANCEL_MEETING`, recipient = requester) and the published queue holds exactly the staged event; false → `Meeting was already canceled, or you are not the host.`; throws → `Failed to cancel the meeting. Please try again later.`. `onDeclineModalOpenFailed` → one ephemeral published, no `UpdateMeetingAttendanceEvent` re-published. `addParticipants`: `ADDED` → `Approval` `MEETING_APPROVAL_REQUEST` to `U_A` and `U_B` plus host ephemeral `Added <@U_A> <@U_B> to the meeting.`; `NOT_AUTHORIZED` → zero approvals, `Meeting was canceled, or you are not the host.`. Optimistic-lock cases inside an H2-backed outer `TransactionTemplate` (the stand-in for `handleInteraction`'s transaction) with a real `SlackOutboundStager` and `CommitRecordingEventPublisher`: add conflicts once → two repository calls, only the retry's approval and `Added <@U_A> to the meeting.` committed; add conflicts twice / cancel conflicts twice → no exception escapes the outer commit and only the try-again reply is committed. Classification: `CannotAcquireLockException` once → retried, `Added <@U_A> to the meeting.`; participant duplicate key once then `NO_NEW_PARTICIPANTS` → retried, `Those people are already on this meeting.`; a not-null integrity violation → one attempt, try-again reply; `OutOfMemoryError` → propagates out of the outer transaction, one attempt, nothing committed. Pool bound: on a two-connection Hikari pool (`createBoundedH2DataSource(2)`) an outer transaction plus a conflict-then-retry still commits `Added <@U_A> to the meeting.` — a retry that held the first attempt's connection would time out after 250 ms. `getMeetingListEvent`: → `Ephemeral(MessageContent.MeetingList(meetings, currentUserId))` published; throws → `ERROR_RESPONSE` `Failed to fetch your meetings. Please try again later.`. |

## For AI Agents

### Working In This Directory
- **Conflict fixtures mimic the production boundary.** `failInsideParticipatingTx` throws from inside a
  `TransactionTemplate` that joins the current transaction, exactly as a `@Transactional` repository method
  does, so it marks the enclosing transaction rollback-only. `CommitRecordingEventPublisher` records a staged
  message only in `afterCommit` of the transaction it was published in, like the `BEFORE_COMMIT` outbox
  listener. Assert on `committedMessages` / `committedEphemeralMarkdowns`, not on `stage` calls, when the
  question is what the user actually receives.
- Canceled-meeting filtering is a repository concern; do not re-add scheduler cases that stub the window query
  empty and call it coverage.
- `MeetingRescheduleServiceTest` and `MeetingServiceImplTest` share spec-scope mocks across `when` blocks;
  Kotest accumulates `verify` counts, so later `when`s either build `local*` mocks (reschedule) or call
  `clearMocks(stager)` first (add participants). Keep that discipline when adding cases.
- Scheduler clocks are `Clock.fixed(12:00 Asia/Seoul, seoul)`; the agenda spec has a second
  `beforeSendInstant` at 07:00. `readyReminderOf` derives `startAt` from `nowInstant + offsetMinutes`, which
  is why the reminder text says `12:15` for the 15-minute offset.
- Claim-token CAS is asserted with two slots (`claimReminder` vs `markReminderSent`) compared for equality;
  `stubPort()`/`stubTransactionManager()` are file-local copies, as in `standup`.
- `buildAgendaDm` is an `internal fun` in `DailyAgendaSchedulingService.kt`; `AgendaItem` is its own file.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.service.meeting.*'
```
Fixtures used: `application` testFixtures `service/meeting/AgendaItemCreator.kt` (`createAgendaItem`,
`createAgendaCandidateMeeting`, `createReminderCandidateMeeting`) and `outbox/OutboxTestFixtures.kt`
(`createOutboxRow`); `domain` testFixtures `command/CommandDomainInputCreator.kt` (`createCommandBasicInfo`),
`meet/MeetingDtoCreator.kt` (`createMeetingDto`, `createMeetingParticipantDto`), `meet/MeetingTestFixtures.kt`
(`createRescheduleMeetingEvent`, `createCancelMeetingEvent`, `createAddParticipantEvent`, `createGetMeetingListEvent`,
`createUpdateMeetingAttendanceEvent`), `meet/MeetingReminderDtoCreator.kt` (`createMeetingReminderDto`);
`application` testFixtures `service/meeting/MeetingTransactionFixtures.kt`;
`infrastructure` testFixtures `impl/command/event/SlackEventTestFixtures.kt` (`createSendSlackMessageEvent`) and
`schema/MeetingSchemaCreator.kt` (`createMeetingSchemaWithParticipant`, in the JPA spec).
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
  `OutboundMessagePort`

### External
MockK (`clearMocks`, `slot`), Kotest, Spring `PlatformTransactionManager` / `TransactionTemplate` /
`JdbcTemplate` / `JpaTransactionManager`, Hibernate, HikariCP, H2 (runtime, through `:infrastructure`),
`DataIntegrityViolationException`, `CannotAcquireLockException`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
