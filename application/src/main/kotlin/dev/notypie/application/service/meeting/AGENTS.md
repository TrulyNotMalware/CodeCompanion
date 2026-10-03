<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-10-03 -->

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
| `MeetingServiceImpl.kt` | `@Service`. `@Transactional handleMeeting` → `RequestMeetingCommand` → `CommandExecutor.execute`. `@TransactionalEventListener(BEFORE_COMMIT, fallbackExecution = false) createNewMeeting(RequestMeetingContextResult)` and `updateParticipantAttendance(UpdateMeetingAttendanceEvent)` (no in-listener retry: they run inside the caller's transaction, where a first failure already marks it rollback-only, so a failure propagates once and rolls the command back); `@EventListener @Transactional onDeclineModalOpenFailed` (own transaction: modals open after the caller's transaction ended, and its outbox write is BEFORE_COMMIT), `cancelMeeting(CancelMeetingEvent)`, `addParticipants(AddParticipantEvent)`, `getMeetingListEvent(GetMeetingListEvent)` — each ends in `outboundStager.stage(...)?.let { eventPublisher.publishOne(it) }`. Cancel and add-participant go through `MeetingWriteDeferral.runOrDefer` and run the repository write **and** the staging of their replies inside `executeRetryingOnConflict`; the failure reply goes through a `REQUIRED` `replyTemplate` via `stageFailureReply`. Also declares the package helpers `isolatedWriteTemplate(transactionManager)` (`TransactionTemplate` with `PROPAGATION_REQUIRES_NEW`) and `TransactionTemplate.executeRetryingOnConflict(action): Result<Unit>`: catches `RuntimeException` only, retries once in a fresh transaction when `isMeetingWriteConflict()` (infrastructure `repository/meeting/MeetingWriteConflict.kt`: any `ConcurrencyFailureException` — optimistic, pessimistic, lock-acquisition — or a `DataIntegrityViolationException` naming the participant unique key), returns any other `RuntimeException` as a failure (the generic reply) and lets an `Error` propagate; and `TransactionTemplate.stageFailureReply(failure, reply)`: a reply that throws a `RuntimeException` too is attached to the write failure with `addSuppressed` instead of escaping, and the caller then logs that failure once at ERROR |
| `MeetingRescheduleService.kt` | `@EventListener rescheduleMeeting(RescheduleMeetingEvent)`, takes the context's `Clock`. A `newStartAt` not after `LocalDateTime.now(clock)` truncated to the minute (the modal's precision, so the current minute counts as past) → "Pick a future time. The meeting was not rescheduled." to the host, staged in the caller's transaction, no write. Otherwise, through `MeetingWriteDeferral.runOrDefer`, inside `executeRetryingOnConflict`: `MeetingRepository.rescheduleMeeting` → `RescheduleResult`: `NotAuthorized` → "Meeting was canceled, or you are not the host."; `AlreadyAtRequestedTime` → "The meeting is already scheduled for <time>. Nothing was changed." and nothing else; `Rescheduled(meeting)` → `MeetingReminderRepository.deleteByMeetingId` so reminders re-materialize, a channel re-notification to the returned participants (title passed through `escapeMrkdwn`, the `<@id>` mentions are the message's own) and "Meeting rescheduled to <time>." (`MEETING_RESCHEDULE_SUBMIT`). Failure after the retry → "Failed to reschedule the meeting. Please try again later." staged in the caller's transaction. `:domain` parses a past start on purpose so it reaches this check |
| `MeetingReminderScheduler.kt` | `@Component`, `@Scheduled(fixedDelay = 60_000) tick()`: `materializeReminders()` then `sendDueReminders()`, each in its own `containFailure` (from `service/standup`: logs an `Exception` at ERROR, restores and rethrows an interrupt), so a failed materialize no longer skips sending due reminders |
| `MeetingReminderSchedulingService.kt` | Phase A `materializeReminders`: one `meeting_reminder` row per `meeting.reminder.offsetsMinutes` for active meetings in `[now - materializeLookbackMinutes, now + maxOffset]`, `ensureReminder(…, startAt = meeting.startAt, now)` idempotent via unique `(meeting_id, offset_minutes)`, and it realigns a `PENDING` row armed for an earlier start. Each meeting runs in its own `containFailure` (ERROR `Meeting reminder materialize failed: meetingId=…`), so one meeting's failure — a real DIVE with no row behind it, or a MariaDB 1020 — no longer ends the tick; the next tick retries it. Phase B `sendDueReminders`: `resetStuckReminders`, `findDueBefore(limit = dispatchBatchSize)`, a row not armed for the current start in the clock's zone (`ReadyReminder.isArmedFor`) is dropped with `discardReminder(id, scheduledAt read)` and never claimed, then claim-token CAS, `runInTx` outbox writes, `markReminderSent` / `markReminderFailed` (`markReminderSent` is `false` when the row was re-claimed or reset, or when the meeting was canceled after the claim; the DMs then roll back and the row is marked failed). `internal fun buildReminderDm` (`MEETING_REMINDER`; the title is escaped in the mrkdwn body, not in the `plain_text` headline, where Slack parses no `<…>`) |
| `DailyAgendaScheduler.kt` | `@Component`, `@Scheduled(fixedDelay = 60_000) tick()` → `sendDailyAgenda()` |
| `DailyAgendaSchedulingService.kt` | Gate on `meeting.agenda.enabled`, on local time ≥ `meeting.agenda.sendAt` in `meeting.agenda.timezone`, then `AgendaDispatchRepository.claim(agendaDate)` (atomic `INSERT IGNORE`) → `findAttendingMeetingsForDay` → fan-out per attending user → `runInTx` outbox writes. `internal fun buildAgendaDm` (`DAILY_AGENDA`; each title escaped) |
| `MeetingWriteDeferral.kt` | `internal object` with a thread-local queue. `collecting { }` (opened by `SlackInteractionHandlerImpl` around its transaction) returns the block's result plus the queued writes; nested calls share the outer queue. `runOrDefer(write)` queues when a scope is open and runs immediately otherwise (schedulers, specs, any caller that is not the interaction handler) |
| `AgendaItem.kt` | `data class AgendaItem(startAt: LocalDateTime, title: String)` — one `• HH:mm — Title` line |

## For AI Agents

### Working In This Directory
- **`BEFORE_COMMIT` listeners ride the caller's transaction.** `createNewMeeting` and
  `updateParticipantAttendance` run inside the `@Transactional` boundary of `handleMeeting` or
  `SlackInteractionHandlerImpl.handleInteraction`; `fallbackExecution = false` means they never run outside
  one. A throw there rolls back the whole command — that is how an unrecorded decision is refused.
- **Zero rows ≠ missing row, depending on the JDBC URL.** MariaDB Connector/J 3.5.10 (the resolved runtime
  version) defaults to `useAffectedRows=false`, which sets the `FOUND_ROWS` capability: an UPDATE reports
  matched rows, so a no-op re-submit (same reason, or `OTHER` after the provisional-OTHER write from the Deny
  click) returns 1. A URL with `useAffectedRows=true` would return 0 for that no-op. The prod/dev URLs come from
  `SQL_DATABASE_URL` / `DATABASE_URL` and were not inspected, so `updateParticipantAttendance` fails only when
  `participantExists` is also false. Keep that check.
- **Meeting writes run after the interaction transaction, in one retryable transaction each.**
  `cancelMeeting`, `addParticipants` and `rescheduleMeeting` hand their work to
  `MeetingWriteDeferral.runOrDefer`. Under `SlackInteractionHandlerImpl.handleInteraction` the work is queued
  and runs only after the interaction transaction has committed and released its connection; if that
  transaction fails, the write never happens. `executeRetryingOnConflict` then runs the write, its effects
  (reminder delete, notices, approvals) and the host reply in one `REQUIRES_NEW` transaction, so the
  `BEFORE_COMMIT` outbox listener binds to it and the reply commits atomically with the write; the retry opens
  a fresh transaction (new persistence context, new REPEATABLE_READ snapshot) and re-reads the row. Only when
  both attempts fail is "Please try again later" staged, through a `REQUIRED` template (its own transaction
  after the interaction, the caller's when run inline). If that reply fails as well (the database is down), the
  failure is added to the write failure as suppressed and logged in the same single ERROR line: deferred, the
  interaction has already committed and must not turn into an HTTP 500; inline, the reply's failure has marked the
  caller's transaction rollback-only, so the caller still fails at commit. The "Pick a future time" reply is staged at event
  time, inside the interaction transaction, and needs no connection of its own. Pinned by
  `MeetingWriteJpaTransactionTest` (real `JpaTransactionManager`: distinct `EntityManager` per attempt, the
  retry applies on top of a concurrent commit) and `SlackInteractionHandlerImplTest` (ordering, discard on
  failure, pool scenario).
- **One pooled connection at a time on the interaction path.** Deferred, a request thread never holds the
  interaction's connection while it waits for the write's, so N concurrent meeting interactions work on a
  pool of N (`SlackInteractionHandlerImplTest`); the retry starts only after the first attempt returned its
  connection (`MeetingServiceImplTest` runs conflict-then-retry on a two-connection pool). The inline path
  (`runOrDefer` with no scope: anything not called through the handler) still takes a second connection
  while its caller holds one; keep such callers out of transactions or size the pool as 2 × their
  concurrency. Keep the write block short: repository read and write, reminder delete, in-memory staging and
  (at its `BEFORE_COMMIT`) the outbox inserts — no Slack or HTTP call, no rendering, no profile lookup, no
  extra read (the reschedule takes its participants from the `RescheduleResult`). An `AFTER_COMMIT`
  listener was not an option: Spring runs `afterCommit` callbacks before it releases the committed
  transaction's connection (`AbstractPlatformTransactionManager.processCommit` → `cleanupAfterCompletion`
  last), so it would still hold two.
- **Resubmits are no-ops.** A user may submit the same modal again (for example after a timeout). A
  reschedule to the time the meeting already has returns `AlreadyAtRequestedTime` (no version bump, no
  reminder delete, no channel notice), a second cancel returns `false`, and a second add of the same users
  returns `NO_NEW_PARTICIPANTS` (no approval request); each only answers the host.
- **Authorization lives in the repository.** Missing / non-host / canceled collapse into `false` /
  `NOT_AUTHORIZED` before any mutation; the `@Version` check (not a bulk `WHERE`) closes the race between the
  check and the write.
- **Reminder lifecycle.** Offsets older than `stuckSendingThresholdMinutes` are skipped at materialize,
  so a late-picked-up meeting gets its nearer reminder but not a stale far one. Reschedule is
  delete-and-recreate: `deleteByMeetingId`, then the next tick re-arms at the new start. A materialize pass
  that read the old start and inserted after the delete leaves a stale row; the next pass realigns it and the
  sender discards it rather than announce the new time at the old moment (both CAS in the repository). Per-reminder
  send uses the same three-transaction claim-token pattern as `service/standup/`.
- **Daily agenda claim and DMs share one transaction.** `claim(agendaDate)`, the day read and every outbox
  write run in one `runInTx`; a failed write rolls the claim back, so the next tick claims the date again.
  Zero-participant meetings come back from the day read with no attending users and simply add no lines. The agenda scheduler uses
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
`DailyAgendaSchedulingServiceTest`, `DailyAgendaMessageBuilderTest` (`buildAgendaDm`), and
`MeetingWriteJpaTransactionTest` (real `MeetingRepositoryImpl` / `MeetingReminderRepositoryImpl` and both
services on `JpaTransactionManager` over H2, no Spring context). Otherwise MockK the
repositories, `OutboundMessagePort`, `OutboundMessageStager`, `EventPublisher`; a fixed `Clock`; assert on
CAS calls, captured outbox rows and staged messages. Fixtures: `createAgendaItem` /
`createAgendaCandidateMeeting` / `createReminderCandidateMeeting` (application testFixtures, same
package), `createMeetingDto`, `createMeetingParticipantDto`, `createMeetingReminderDto`,
`createCancelMeetingEvent`, `createRescheduleMeetingEvent`, `createAddParticipantEvent`, `createUpdateMeetingAttendanceEvent`,
`createGetMeetingListEvent` (domain testFixtures `dev.notypie.domain.meet`), `createCommandBasicInfo`,
`createSendSlackMessageEvent`, `createOutboxRow`, and `MeetingTransactionFixtures.kt` (`createH2DataSource`,
`createH2TransactionManager`, `createBoundedH2DataSource`, `createFixedClock`, `createMeetingVersionConflict`,
`createParticipantDuplicateKeyViolation`, `createNotNullViolation`, `failInsideParticipatingTx`,
`CommitRecordingEventPublisher`, `createH2MeetingJpaStore`) for the transaction-boundary cases, which run
against a real `DataSourceTransactionManager` or `JpaTransactionManager` over in-memory H2 without a Spring
context. Repository write semantics
(guards, version bumps, races) are H2-covered in `infrastructure/src/test/kotlin/dev/notypie/repository/meeting/`.

### Common Patterns
- Thin `@Scheduled` `*Scheduler` → `*SchedulingService`; specs target the service.
- `TransactionTemplate(transactionManager)` in the constructor + `runInTx` returning `Result` (schedulers);
  `isolatedWriteTemplate(transactionManager)` + `executeRetryingOnConflict { … }.onFailure { … }` (meeting
  writes), mapping failures to a user-facing ephemeral with `log.error` carrying `meetingUid`, `requesterId`,
  `idempotencyKey`.
- `internal` top-level DM builders (`buildReminderDm`, `buildAgendaDm`) tested without the scheduler.
- `AppConfig.meeting.reminder.*` (`offsetsMinutes`, `stuckSendingThresholdMinutes`, `dispatchBatchSize`,
  `materializeLookbackMinutes`) and `AppConfig.meeting.agenda.*` (`enabled`, `sendAt`, `timezone`).

## Dependencies

### Internal
- `infrastructure/repository/meeting/` — `MeetingRepository`, `MeetingReminderRepository`,
  `AgendaDispatchRepository`, `ReadyReminder`, `ReminderCandidateMeeting`, `AgendaCandidateMeeting`,
  `AddParticipantResult`, `RescheduleResult`, `isMeetingWriteConflict`
- `infrastructure/repository/outbox/` — `MessageOutboxRepository`, `OutboundMessagePort`
- `infrastructure/impl/command/slack/SlashCommandRequestBody`, `infrastructure/templates/escapeMrkdwn`
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
