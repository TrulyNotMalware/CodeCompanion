<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# application/service/meeting

## Purpose
The `/meetup` lane. `MeetingServiceImpl` is the slash entry point and the listener for every meeting
event the domain contexts emit: persisting a new meeting at `BEFORE_COMMIT`, recording Accept/Decline
decisions, the decline-modal fallback, host-only cancel, add-participant, and the meeting list.
`MeetingRescheduleService` handles the reschedule modal. Two schedulers sit beside them: pre-meeting
reminder DMs at configured offsets, and a once-per-day morning agenda DM per user.

## Key Files
| File | Description |
|------|-------------|
| `MeetingService.kt` | Interface `handleMeeting(headers, payload: SlashCommandRequestBody, commandData)` taken by `SlashCommandController` and `SocketModeReceiver` |
| `MeetingServiceImpl.kt` | `@Service`. `@Transactional handleMeeting` → `RequestMeetingCommand` → `CommandExecutor.execute`. `@TransactionalEventListener(BEFORE_COMMIT, fallbackExecution = false) createNewMeeting(RequestMeetingContextResult)` and `updateParticipantAttendance(UpdateMeetingAttendanceEvent)` (both through `RetryService`); `@EventListener onDeclineModalOpenFailed`, `cancelMeeting(CancelMeetingEvent)`, `addParticipants(AddParticipantEvent)`, `getMeetingListEvent(GetMeetingListEvent)` — each ends in `outboundStager.stage(...)?.let { eventPublisher.publishOne(it) }` |
| `MeetingRescheduleService.kt` | `@Transactional @EventListener rescheduleMeeting(RescheduleMeetingEvent)`: `MeetingRepository.rescheduleMeeting` (host-only via the repository WHERE clause), then `MeetingReminderRepository.deleteByMeetingId` so reminders re-materialize, a channel re-notification to participants and an ephemeral to the host (`MEETING_RESCHEDULE_SUBMIT`) |
| `MeetingReminderScheduler.kt` | `@Component`, `@Scheduled(fixedDelay = 60_000) tick()`: `materializeReminders()` then `sendDueReminders()` inside one `runCatching` |
| `MeetingReminderSchedulingService.kt` | Phase A `materializeReminders`: one `meeting_reminder` row per `meeting.reminder.offsetsMinutes` for active meetings in `[now - materializeLookbackMinutes, now + maxOffset]`, `ensureReminder` idempotent via unique `(meeting_id, offset_minutes)`. Phase B `sendDueReminders`: `resetStuckReminders`, `findDueBefore(limit = dispatchBatchSize)`, claim-token CAS, `runInTx` outbox writes, `markReminderSent` / `markReminderFailed`. `internal fun buildReminderDm` (`MEETING_REMINDER`) |
| `DailyAgendaScheduler.kt` | `@Component`, `@Scheduled(fixedDelay = 60_000) tick()` → `sendDailyAgenda()` |
| `DailyAgendaSchedulingService.kt` | Gate on `meeting.agenda.enabled`, on local time ≥ `meeting.agenda.sendAt` in `meeting.agenda.timezone`, then `AgendaDispatchRepository.claim(agendaDate)` (atomic `INSERT IGNORE`) → `findAttendingMeetingsForDay` → fan-out per attending user → `runInTx` outbox writes. `internal fun buildAgendaDm` (`DAILY_AGENDA`) |
| `AgendaItem.kt` | `data class AgendaItem(startAt: LocalDateTime, title: String)` — one `• HH:mm — Title` line |

## For AI Agents

### Working In This Directory
- **`BEFORE_COMMIT` listeners ride the caller's transaction.** `createNewMeeting` and
  `updateParticipantAttendance` run inside the `@Transactional` boundary of `handleMeeting` or
  `SlackInteractionHandlerImpl.handleInteraction`; `fallbackExecution = false` means they never run outside
  one. A throw there rolls back the whole command — that is how an unrecorded decision is refused.
- **Zero rows ≠ missing row.** MariaDB's default `CLIENT_FOUND_ROWS=false` returns 0 for a no-op UPDATE
  (re-submitting the same reason, or `OTHER` after the provisional-OTHER write from the Deny click).
  `updateParticipantAttendance` fails only when `participantExists` is also false. Keep that check.
- **Authorization is the WHERE clause.** Cancel, reschedule and add-participant are host-only because the
  repository UPDATE matches `requester == publisher_id AND is_canceled = false`; a `false` / `Outcome`
  collapses missing / non-host / canceled into one friendly ephemeral. Do not add a read-then-check —
  it introduces a race the atomic UPDATE avoids.
- **Reminder lifecycle.** Offsets older than `stuckSendingThresholdMinutes` are skipped at materialize,
  so a late-picked-up meeting gets its nearer reminder but not a stale far one. Reschedule is
  delete-and-recreate: `deleteByMeetingId`, then the next tick re-arms at the new start. Per-reminder
  send uses the same three-transaction claim-token pattern as `service/standup/`.
- **Daily agenda is at-most-once per date.** The `claim(agendaDate)` commits before the DMs are enqueued;
  a failed `runInTx` is logged and that date is not retried. The agenda scheduler uses
  `meeting.agenda.timezone`; the reminder scheduler uses the injected `Clock`'s zone.
- **Two outbound styles.** Schedulers write rows directly with
  `CommandBasicInfo.forOutbound(publisherId = userId, channel = userId)`; listeners stage through
  `OutboundMessageStager` + `eventPublisher.publishOne`. `notifyAddedParticipants` keys its `Approval` on
  the meeting's own `idempotencyKey` so the recipient's Accept/Decline hits the right participant row.
- Events consumed: `RequestMeetingContextResult`, `UpdateMeetingAttendanceEvent`,
  `DeclineModalOpenFailedEvent`, `CancelMeetingEvent`, `AddParticipantEvent`, `GetMeetingListEvent`,
  `RescheduleMeetingEvent` — emitted by `domain/command/entity/context/form/*` (e.g.
  `RequestMeetingContext`, `MeetingApprovalResponseContext`) and lifted by `SlackIntentResolver` /
  `ApplicationMessageDispatcher`.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.service.meeting.*'
```
Specs under `application/src/test/kotlin/dev/notypie/application/service/meeting/`:
`MeetingServiceImplTest`, `MeetingRescheduleServiceTest`, `MeetingReminderSchedulingServiceTest`,
`DailyAgendaSchedulingServiceTest`, `DailyAgendaMessageBuilderTest` (`buildAgendaDm`). MockK the
repositories, `OutboundMessagePort`, `OutboundMessageStager`, `EventPublisher`; a fixed `Clock`; assert on
CAS calls, captured outbox rows and staged messages. Fixtures: `createAgendaItem` /
`createAgendaCandidateMeeting` / `createReminderCandidateMeeting` (application testFixtures, same
package), `createMeetingDto`, `createMeetingParticipantDto`, `createMeetingReminderDto`,
`createCancelMeetingEvent`, `createRescheduleMeetingEvent`, `createUpdateMeetingAttendanceEvent`,
`createGetMeetingListEvent` (domain testFixtures `dev.notypie.domain.meet`), `createCommandBasicInfo`,
`createSendSlackMessageEvent`, `createOutboxRow`. Repository SQL semantics (host-only UPDATEs,
`addParticipants`) are H2-covered in `infrastructure/src/test/kotlin/dev/notypie/repository/meeting/`.

### Common Patterns
- Thin `@Scheduled` `*Scheduler` → `*SchedulingService`; specs target the service.
- `TransactionTemplate(transactionManager)` in the constructor + `runInTx` returning `Result`.
- `runCatching { repository call }.fold / getOrElse` mapping failures to a user-facing ephemeral with
  `log.error` carrying `meetingUid`, `requesterId`, `idempotencyKey`.
- `internal` top-level DM builders (`buildReminderDm`, `buildAgendaDm`) tested without the scheduler.
- `AppConfig.meeting.reminder.*` (`offsetsMinutes`, `stuckSendingThresholdMinutes`, `dispatchBatchSize`,
  `materializeLookbackMinutes`) and `AppConfig.meeting.agenda.*` (`enabled`, `sendAt`, `timezone`).

## Dependencies

### Internal
- `infrastructure/repository/meeting/` — `MeetingRepository`, `MeetingReminderRepository`,
  `AgendaDispatchRepository`, `ReadyReminder`, `ReminderCandidateMeeting`, `AgendaCandidateMeeting`,
  `AddParticipantResult`
- `infrastructure/repository/outbox/` — `MessageOutboxRepository`, `OutboundMessagePort`
- `infrastructure/impl/retry/RetryService`, `infrastructure/impl/command/slack/SlashCommandRequestBody`
- `domain/meet/` — `Meeting` (`MAX_PARTICIPANTS`), `MeetingDto`, `RejectReason`
- `domain/command/entity/slash/` — `RequestMeetingCommand`, `RequestMeetingContextResult`
- `domain/command/entity/event/` — the events listed above, `EventPublisher.publishOne`
- `domain/command/outbound/`, `domain/command/dto/modals/ApprovalContents`,
  `domain/command/dto/CommandBasicInfo`
- `application/service/command/CommandExecutor`, `application/common/` (`IdempotencyCreator`, `runInTx`),
  `application/configurations/AppConfig`

### External
Spring scheduling / transaction events / context events, kotlin-logging.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
