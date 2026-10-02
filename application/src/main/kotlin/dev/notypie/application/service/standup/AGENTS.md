<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-10-01 -->

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
| `StandupRoutineSetupService.kt` | `@EventListener createRoutine(CreateStandupRoutineEvent)`: builds `Routine` + one `RoutineMember` per id (each member adopts the routine timezone), `StandupRepository.createRoutine`, then stages an `OutboundMessage.Ephemeral` confirmation or rejection (`STANDUP_SETUP_SUBMIT`) to `payload.commandChannel` (target **and** `basicInfo.channel` — the view_submission's `responseBasicInfo.channel` is always `""`, review T5) and `eventPublisher.publishOne`. `Routine.init` is the only validator (plus a `null` `cutoffMinutes`, meaning the typed cutoff was not a whole number in `1..1440`); its throw becomes the rejection text |
| `StandupAnswerService.kt` | `@EventListener recordAnswer(RecordStandupAnswerEvent)` → `StandupRepository.recordAnswer` (the transaction lives in the repository impl, not here), then stages `UpdateMessage` on `payload.notice`: `RECORDED` → `SUBMITTED_NOTICE` ("Standup submitted."), `SESSION_CLOSED` → `CLOSED_NOTICE` (answer not recorded), `SESSION_NOT_FOUND` → warn only (review T19); `@EventListener onStandupModalOpenFailed(StandupModalOpenFailedEvent)` stages an `Ephemeral` with `recipient = null` into the originating DM channel |
| `StandupScheduler.kt` | `@Component`, `@Scheduled(fixedDelay = 60_000) tick()`: `openSessionsForToday` → `sendPendingDispatches` → `nudgeNonResponders` → `detectCutoffs`, each phase in its own `runCatching` so one failing phase cannot skip the later ones (review T2) |
| `StandupSchedulingService.kt` | `@Service` owning the four phases, a `TransactionTemplate` built from the injected `PlatformTransactionManager`, and the `internal` builders `buildDmNotice` (`OutboundMessage.Approval`, buttons "Fill in standup" / "Skip", `STANDUP_PROMPT`, `routingExtras = [sessionUid, routineUid]`) and `buildNudgeNotice` (`ChannelMessage`). Publishes `StandupCutoffEvent` through a plain `ApplicationEventPublisher` |
| `StandupSummaryService.kt` | `@EventListener postSummary(StandupCutoffEvent)`: one `runInTx` that reads the session with `findSessionForSummary` (session row locked `PESSIMISTIC_WRITE`, answers loaded under the lock), returns without writing when it is missing or no longer `COLLECTING`, loads the routine, builds the `MessageContent.StandupSummary` row, `outboxRepository.save(row)` and `markSessionSummarized(messageTs = "outbox:<eventId>")` — a `false` from the CAS throws so the row rolls back. `@EventListener replaceSummaryMarkerWithSlackTs(MessagePublishSuccessEvent)` swaps the marker for the real Slack `ts` |

## For AI Agents

### Working In This Directory
- **Phase order is load-bearing.** Open sessions before sending, send before nudging, nudge before cutoff.
  `openSessionForRoutine` is idempotent through the unique `(routine_uid, session_date)` constraint; the
  `DataIntegrityViolationException` is swallowed only after `findSession` confirms the row exists — any
  other violation is re-raised out of `openSessionForRoutine` and surfaces as that routine's error log.
- **Failure isolation is per phase, per routine, per session.** The tick wraps each phase;
  `openSessionsForToday` wraps each routine and `detectCutoffs` wraps each session's (synchronous) summary
  listener. Before this, one routine with an overflowing cutoff (`cutoffAnchor.plus(cutoffOffset)` →
  `DateTimeException`) stopped DMs, nudges and summaries for every routine on every tick (T2, U6).
- **Per-member timezone.** `dmTriggerAt` is `today@triggerLocalTime` in the *member's* zone; `cutoffAt` is
  the **latest** member trigger plus `cutoffOffset`, so a westward member still gets the full window.
  `sendPendingDispatches` queries by absolute instant across all sessions, never "today in routine zone".
- **Closed sessions get no DM.** A ready dispatch whose session is not `COLLECTING` or whose `cutoffAt` has
  passed is marked `SKIPPED` (`markDispatchSkipped`) instead of sending a "Fill in" DM (review T19).
  A dispatch whose routine is inactive / unknown is marked `SKIPPED` too: left `PENDING`, such rows kept
  winning `ORDER BY dm_trigger_at` + `dispatchBatchSize` and starved every active routine (review T28).
- **Dispatch CAS with a claim token.** `resetStuckDispatches(olderThan)` runs first. Then per row:
  `claimDispatch(dispatchId, claimToken)`, `buildDmNotice` + `outboxRepository.save(outboundMessagePort.toRow(...))`
  and `markDispatchSent(claimToken)` run in **one** `runInTx` (the daily agenda's N1 fix). Any failure rolls
  the claim back to `PENDING` and the next tick retries; the retries are bounded by cutoff, where the
  closed-session rule above marks the row `SKIPPED`. Before this, the claim committed first and one
  transient error ended the member's day in terminal `FAILED` (review T18). The claim `UPDATE ... WHERE
  dm_status = 'PENDING'` row-locks, so a concurrent tick blocks and then matches nothing — no double DM.
- **Nudge is at-most-once, and the claim joins the enqueue.** `claimNudge(sessionId)` is taken only when
  non-responders exist, inside the same `runInTx` as the outbox saves, so a failed enqueue un-claims the
  session and the next tick retries while `cutoffAt > now` still selects it (review T18).
  `standup.nudge.offsetMinutes <= 0` disables the phase entirely.
- **Answers and the summary are serialized on the session row (review G5 / Codex R5).** `recordAnswer` takes
  `PESSIMISTIC_WRITE` on the session before it checks status and cutoff, and `postSummary` takes the same lock
  before it reads the answers, in the transaction that saves the summary and flips the session `SUMMARIZED`.
  An answer therefore commits before the summary reads (and is in it) or waits and is told the session closed.
  Do not move the session read back out of that transaction: read first and written later, an answer that
  committed in between was acknowledged as submitted and missing from the summary.
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
`StandupSchedulingServiceTest` also runs the dispatch / nudge claims against an H2
`DataSourceTransactionManager` (`createH2DataSource` / `createH2TransactionManager` from the meeting
testFixtures) to prove the claim rolls back with a failed enqueue. The repository's own SQL is covered by
`infrastructure/.../repository/standup/StandupRepositoryImplJpaTest`.

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
