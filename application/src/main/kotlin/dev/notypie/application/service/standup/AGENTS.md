<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

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
| `StandupRoutineSetupService.kt` | `@EventListener createRoutine(CreateStandupRoutineEvent)`: builds `Routine` + one `RoutineMember` per id (each member adopts the routine timezone), `StandupRepository.createRoutine`, then stages an `OutboundMessage.Ephemeral` confirmation or rejection (`STANDUP_SETUP_SUBMIT`) and `eventPublisher.publishOne`. `Routine.init` is the only validator; its throw becomes the rejection text |
| `StandupAnswerService.kt` | `@EventListener recordAnswer(RecordStandupAnswerEvent)` → `StandupRepository.recordAnswer` (the transaction lives in the repository impl, not here); `@EventListener onStandupModalOpenFailed(StandupModalOpenFailedEvent)` stages an `Ephemeral` with `recipient = null` into the originating DM channel |
| `StandupScheduler.kt` | `@Component`, `@Scheduled(fixedDelay = 60_000) tick()`: `openSessionsForToday` → `sendPendingDispatches` → `nudgeNonResponders` → `detectCutoffs`, one `runCatching` around the whole tick |
| `StandupSchedulingService.kt` | `@Service` owning the four phases, a `TransactionTemplate` built from the injected `PlatformTransactionManager`, and the `internal` builders `buildDmNotice` (`OutboundMessage.Approval`, buttons "Fill in standup" / "Skip", `STANDUP_PROMPT`, `routingExtras = [sessionUid, routineUid]`) and `buildNudgeNotice` (`ChannelMessage`). Publishes `StandupCutoffEvent` through a plain `ApplicationEventPublisher` |
| `StandupSummaryService.kt` | `@EventListener postSummary(StandupCutoffEvent)`: builds a `MessageContent.StandupSummary` outbox row, then `runInTx { outboxRepository.save(row); markSessionSummarized(messageTs = "outbox:<eventId>") }` — a `false` from the CAS throws so the row rolls back. `@EventListener replaceSummaryMarkerWithSlackTs(MessagePublishSuccessEvent)` swaps the marker for the real Slack `ts` |

## For AI Agents

### Working In This Directory
- **Phase order is load-bearing.** Open sessions before sending, send before nudging, nudge before cutoff.
  `openSessionForRoutine` is idempotent through the unique `(routine_uid, session_date)` constraint; the
  `DataIntegrityViolationException` is swallowed only after `findSession` confirms the row exists — any
  other violation must surface.
- **Per-member timezone.** `dmTriggerAt` is `today@triggerLocalTime` in the *member's* zone; `cutoffAt` is
  the **latest** member trigger plus `cutoffOffset`, so a westward member still gets the full window.
  `sendPendingDispatches` queries by absolute instant across all sessions, never "today in routine zone".
- **Dispatch CAS with a claim token.** `resetStuckDispatches(olderThan)` runs first. Then per row:
  `claimDispatch(dispatchId, claimToken)` commits in its own tx; `buildDmNotice` +
  `outboxRepository.save(outboundMessagePort.toRow(...))` + `markDispatchSent(claimToken)` run in one
  `runInTx` that rolls back when the mark is a no-op (recovery raced us); `markDispatchFailed(claimToken)`
  records failure in a fresh tx. Only the token that claimed may acknowledge.
- **Nudge is at-most-once by design.** `claimNudge(sessionId)` is taken only when non-responders exist and
  commits *before* the DMs are enqueued; an enqueue failure after the claim is logged, not retried.
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
`StandupRoutineSetupServiceTest`, `StandupDispatchMessageBuilderTest` (`buildDmNotice` /
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
