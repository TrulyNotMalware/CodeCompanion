<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-10-02 -->

# application/service/standup

## Purpose
The daily-standup lane. `/standup setup` opens a modal whose submission creates a `Routine`; a one-minute
scheduler then opens today's session per routine, DMs each member a "Fill in standup" prompt in their own
timezone, nudges non-responders once before cutoff, and detects cutoff. Answers submitted from the modal
are recorded per session, and after cutoff a channel summary is written to the outbox and its Slack `ts`
is written back onto the session row once the relay has posted it.

## Key Files
| File | Description |
|------|-------------|
| `StandupSlashService.kt` | Interface `handleStandup(headers, payload: SlashCommandRequestBody, commandData)` — the dependency `SlashCommandController` and `SocketModeReceiver` take |
| `StandupSlashServiceImpl.kt` | `@Service`, `@Transactional handleStandup`: `IdempotencyCreator.create(data = commandData)` → `SetupStandupCommand` → `CommandExecutor.execute` (the resolved intent is the synchronous `views.open` of the setup modal) |
| `StandupRoutineSetupService.kt` | `@EventListener createRoutine(CreateStandupRoutineEvent)`: builds `Routine` + one `RoutineMember` per id (each member adopts the routine timezone), `StandupRepository.createRoutine`, then stages an `OutboundMessage.Ephemeral` confirmation, or a rejection when building the `Routine` (input validation) throws; a failed `createRoutine` write propagates, since it left the caller's transaction rollback-only (`STANDUP_SETUP_SUBMIT`) and `eventPublisher.publishOne`. `Routine.init` (plus the `requireNotNull` on an unusable cutoff) is the only validator; its throw becomes the rejection text, escaped with `escapeMrkdwn`, and the confirmation escapes the routine name (the `<@id>` member mentions stay markup). Both replies go to `payload.commandChannel` with `basicInfo` copied to that channel: a view_submission's `responseBasicInfo.channel` is `""`, so replying there failed with `channel_not_found` |
| `StandupAnswerService.kt` | `@EventListener recordAnswer(RecordStandupAnswerEvent)` → `StandupRepository.recordAnswer` (joins the interaction transaction, so the session row lock is held until it commits), then collapses the DM prompt (`payload.notice`, when ferried) with the outcome: `RECORDED` → `SUBMITTED_NOTICE` ("Standup submitted."), `SESSION_CLOSED` → `CLOSED_NOTICE`, `SESSION_NOT_FOUND` → `SESSION_NOT_FOUND_NOTICE` — a late answer is never told "submitted"; `@EventListener @Transactional onStandupModalOpenFailed(StandupModalOpenFailedEvent)` (its own transaction, because modals now open after the caller's transaction ended and the outbox write is BEFORE_COMMIT) stages an `Ephemeral` with `recipient = null` into the originating DM channel |
| `StandupScheduler.kt` | `@Component`, `@Scheduled(fixedDelay = 60_000) tick()`: `openSessionsForToday` → `sendPendingDispatches` → `nudgeNonResponders` → `detectCutoffs`, each phase in its own `containFailure` so one failing phase no longer skips the later ones |
| `StandupSchedulingService.kt` | `@Service` owning the four phases, a `TransactionTemplate` built from the injected `PlatformTransactionManager`, and the `internal` builders `buildDmNotice` (`OutboundMessage.Approval`, buttons "Fill in standup" / "Skip", `STANDUP_PROMPT`, `routingExtras = [sessionUid, routineUid]`) and `buildNudgeNotice` (`ChannelMessage`; the creator-chosen routine name is passed through `escapeMrkdwn`, the template's own `*bold*` is not). Publishes `StandupCutoffEvent` through a plain `ApplicationEventPublisher` |
| `StandupSummaryService.kt` | `@EventListener postSummary(StandupCutoffEvent)`: one `runInTx` reads the session under its row lock (`findSessionForSummary`, the same lock `recordAnswer` takes), skips a session that is no longer `COLLECTING`, builds a `MessageContent.StandupSummary` outbox row, saves it and runs `markSessionSummarized(messageTs = "outbox:<eventId>")` — a `false` from the CAS throws so the row rolls back. An answer acknowledged as submitted is therefore in the summary, and one that waited on the lock is told the session closed. `@EventListener replaceSummaryMarkerWithSlackTs(MessagePublishSuccessEvent)` swaps the marker for the real Slack `ts`. The summary may be several messages: the parts come from the pure `ModalTemplateBuilder.standupSummaryParts` (each part's rendered block text within `SlackBlockLimits.MESSAGE_TEXT_BUDGET`, 12,000 — Slack rejects a message near 13,200 characters with `msg_blocks_too_long` — and at most 48 members, labelled `(n/total)`), and this listener stages them as one chained row (`outboundMessagePort.toChainHead`): the first part, with the rest posted by the relay one after another. The row and the `SUMMARIZED` CAS share the transaction, and the marker is that row's `outbox:<eventId>`, so it is the first part's `ts` that replaces it (later parts are new rows with their own ids). Each member's stored answers are `boundedForSummary()` first (`SUMMARY_MEMBER_RESPONSE_CHARS` = the section budget, split across that member's responses; control characters other than line breaks and tabs are dropped, because each costs 14 bytes in the chain head's CDC update record and only without them does a full 30-member summary stay within Kafka's 1 MiB — `OutboxPayloadSizeGuardTest`); the old 18,000-character total cap is gone |

## For AI Agents

### Working In This Directory
- **Phase order is load-bearing.** Open sessions before sending, send before nudging, nudge before cutoff.
  `openSessionForRoutine` is idempotent through the unique `(routine_uid, session_date)` constraint; the
  `DataIntegrityViolationException` is swallowed only after `findSession` confirms the row exists — any
  other violation must surface, and it does, but only for its own routine.
- **Failures are contained per routine, per session and per phase, never per `Throwable`.**
  `containFailure` (`internal inline`, `StandupSchedulingService.kt`) logs an `Exception` and moves on;
  an `InterruptedException` is rethrown with the interrupt flag restored, and an `Error` propagates.
  `openSessionsForToday` contains each routine (one routine's bad data, such as a cutoff that overflows
  `Instant`, no longer blocks the rest), `detectCutoffs` contains each session (the summary listener runs
  synchronously inside `publishEvent`), and `StandupScheduler.tick` contains each phase. Do not wrap these in
  `runCatching`: it catches `Throwable` and swallows interrupts.
- **Per-member timezone.** `dmTriggerAt` is `today@triggerLocalTime` in the *member's* zone; `cutoffAt` is
  the **latest** member trigger plus `cutoffOffset`, so a westward member still gets the full window.
  `sendPendingDispatches` queries by absolute instant across all sessions, never "today in routine zone".
- **A ready dispatch that can no longer help is retired, not left `PENDING`.** A session that is not
  `COLLECTING` or whose `cutoffAt` is not after now → `markDispatchSkipped("session closed before the DM was
  sent")` (the DM would invite an answer that can no longer count); an unknown or inactive routine →
  `markDispatchSkipped("routine inactive")`. Left `PENDING`, such rows kept winning
  `ORDER BY dm_trigger_at` + the batch limit and starved every active routine's DMs.
- **Dispatch CAS with a claim token, the claim inside the enqueue transaction.** `resetStuckDispatches` runs
  first. Then per row one `runInTx` holds `claimDispatch(dispatchId, claimToken)`, `buildDmNotice`,
  `outboxRepository.save(outboundMessagePort.toRow(...))` and `markDispatchSent(claimToken)` (a no-op mark —
  recovery raced us — throws and rolls back). Any failure rolls the claim back too, so the row stays `PENDING`
  and the next tick retries; previously a committed claim ended in terminal `FAILED` after one transient error
  and the member never got that day's DM. The cause is then stored on the still-`PENDING` row
  (`recordDispatchFailure`, its own transaction, contained) and retries stop at cutoff, where the row is skipped
  with `"enqueue failed until cutoff: <cause>"` instead of "session closed before the DM was sent", so the real
  reason survives. A row that keeps failing is retried every tick until cutoff and takes a batch slot each time.
- **Nudge claim inside the enqueue transaction.** `claimNudge(sessionId)` is taken only when non-responders
  exist, in the same `runInTx` as the DMs; a failed enqueue rolls `nudged_at` back and the next tick retries
  while the session is still in the nudge window (`cutoffAt > now`), which bounds the retries.
  `standup.nudge.offsetMinutes <= 0` disables the phase entirely.
- **Summary marker.** `summary_message_ts` holds `outbox:<eventId>` until the relay posts; the write-back
  `UPDATE` is keyed on that marker, so it is a cheap no-op for every non-standup
  `MessagePublishSuccessEvent`. Do not add a `commandDetailType` to the relay event to short-circuit it.
- **Two outbound styles, on purpose.** Scheduler phases write outbox rows directly with
  `CommandBasicInfo.forOutbound(publisherId = userId, channel = userId)` (a tick has no request context or
  `trigger_id`). Event-driven services go through `OutboundMessageStager.stage(...)?.let {
  eventPublisher.publishOne(it) }`. Keep each path where it is.
- Events consumed: `CreateStandupRoutineEvent`, `RecordStandupAnswerEvent`, `StandupModalOpenFailedEvent`
  (lifted from domain contexts by `SlackIntentResolver` / `ApplicationMessageDispatcher`),
  `StandupCutoffEvent` (produced here), `MessagePublishSuccessEvent` (produced by `service/relay/`).
  The answer modal itself is opened by `domain/.../context/form/StandupFillContext` from the DM button.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.service.standup.*'
```
Specs under `application/src/test/kotlin/dev/notypie/application/service/standup/`:
`StandupSchedulingServiceTest`, `StandupSummaryServiceTest`, `StandupAnswerServiceTest`,
`StandupRoutineSetupServiceTest`, `StandupSchedulerTest`, `StandupDispatchMessageBuilderTest` (`buildDmNotice` /
`buildNudgeNotice`). MockK the `StandupRepository`, `MessageOutboxRepository`, `OutboundMessagePort` and
`OutboundMessageStager`; pass a MockK `PlatformTransactionManager` and a fixed `Clock`; assert on the
CAS calls and captured outbox rows / staged messages. Fixtures: `createNudgeCandidateSession`
(application testFixtures, same package), `createRoutineDto` / `createRoutineMemberDto` /
`createSessionDispatchDto` / `createStandupSessionDto` and `createCreateStandupRoutineEvent`,
`createCommandBasicInfo` (domain testFixtures), `createOutboxRow` (`dev.notypie.application.outbox`).
There is no H2 spec for the standup repository CAS methods — assert orchestration here.

### Common Patterns
- Thin `@Scheduled` `*Scheduler` → logic in `*SchedulingService`; specs target the service only.
- Tunables from `AppConfig`: `standup.scheduler.stuckSendingThresholdMinutes`,
  `standup.scheduler.dispatchBatchSize`, `standup.nudge.offsetMinutes`.
- `TransactionTemplate.runInTx { ... }` returning `Result<T>`; `error(...)` inside the block forces rollback.
- `internal` top-level message builders so text can be tested without the scheduler.
- File-level `private val log / answerLog / setupLog / summaryLog = KotlinLogging.logger {}`; log lines carry
  `sessionUid`, `routineUid`, `dispatchId`, `idempotencyKey`.

## Dependencies

### Internal
- `infrastructure/repository/standup/` — `StandupRepository`, `ReadyDispatch`, `NudgeCandidateSession`
- `infrastructure/repository/outbox/` — `MessageOutboxRepository`, `OutboundMessagePort`,
  `dto/MessagePublishSuccessEvent`
- `infrastructure/impl/command/slack/SlashCommandRequestBody`
- `domain/standup/` — `Routine`, `RoutineMember`, `StandupSession`, `SessionDispatch`, `RoutineDto`
- `domain/command/entity/event/` — `CreateStandupRoutineEvent`, `RecordStandupAnswerEvent`,
  `StandupModalOpenFailedEvent`, `StandupCutoffEvent`, `EventPublisher.publishOne`
- `domain/command/entity/slash/SetupStandupCommand`, `domain/command/outbound/` (`OutboundMessage`,
  `MessageContent.StandupSummary`, `OutboundMessageStager`), `domain/command/dto/modals/ApprovalContents`
- `application/service/command/CommandExecutor`, `application/common/` (`IdempotencyCreator`, `runInTx`),
  `application/configurations/AppConfig`

### External
Spring scheduling / transaction / context events, kotlin-logging.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
