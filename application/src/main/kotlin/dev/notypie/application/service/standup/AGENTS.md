<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-10-07 -->

# application/service/standup

## Purpose
The daily-standup lane. `/standup setup` opens a modal whose submission creates a `Routine`, and
`/standup list` / `/standup stop <routine-name>` list or deactivate the channel's active routines; a one-minute
scheduler then opens today's session per routine, DMs each member a "Fill in standup" prompt in their own
timezone, nudges non-responders once before cutoff, and detects cutoff. Answers submitted from the modal
are recorded per session, and after cutoff a channel summary is written to the outbox and its Slack `ts`
is written back onto the session row once the relay has posted it.

## Key Files
| File | Description |
|------|-------------|
| `StandupSlashService.kt` | Interface `handleStandup(headers, payload: SlashCommandRequestBody, commandData)` — the dependency `SlashCommandController` and `SocketModeReceiver` take |
| `StandupSlashServiceImpl.kt` | `@Service`, `@Transactional handleStandup`: `IdempotencyCreator.create(data = commandData)` → `StandupCommand` → `CommandExecutor.execute` (for `setup` the resolved effect is the synchronous `views.open` of the setup modal; `list` / `stop` resolve to a `StandupOpsRequestEvent`; the `CommandOutput` is discarded, so a reply the user must see has to be an outbound effect) |
| `StandupRoutineOpsService.kt` | `@Service`, `@Transactional @EventListener handleStandupOps(StandupOpsRequestEvent)`. `LIST` → `findActiveRoutinesByChannel(basicInfo.channel)` rendered as one bullet per routine (name escaped with `escapeMrkdwn`, `HH:mm` + zone id, weekdays `Mon/Tue/…` in week order, `cutoff +<minutes>m`, member count, summary channel, creator), or a pointer to `/standup setup` when empty. `STOP` → `lockActiveRoutinesByChannel(basicInfo.channel)` first (a `SELECT … FOR UPDATE`, the STOP path's first database statement), then the candidates whose `Routine.normalizeName` matches the requested name case-insensitively: none → "No active standup routine named …"; one → that routine; several → the one whose `creatorId` is the requester, or, when none is, "Several active routines are named `X` in this channel; ask their creators to stop theirs." with nothing stopped (admins included — no arbitrary pick). Only the routine's `creatorId` or a user whose `CommandRoleResolver.resolve` role grants `CommandPermission.ADMINISTRATION` may stop it (the creator skips the role lookup); the lookup runs through `detachedTemplate` (`PROPAGATION_NOT_SUPPORTED`), so a failing `@Transactional` role read cannot mark the slash transaction rollback-only; `deactivateRoutine` returning `false` → "already stopped". Every reply is an `OutboundMessage.Ephemeral` to the requester in the command channel (`recipient = UserRef(publisherId)`, headline "CodeCompanion — standup", `STANDUP_ROUTINE_LIST` / `STANDUP_ROUTINE_STOP`), staged through `OutboundMessageStager` (`checkNotNull`) and `eventPublisher.publishOne`. A stopped routine opens no new session, and later `sendPendingDispatches` / `nudgeNonResponders` ticks skip it (each tick snapshots `listActiveRoutines()`); outbox rows a tick already enqueued are still delivered, and a session already `COLLECTING` is still summarized at cutoff, because `findCollectingSessionsPastCutoff` and `StandupSummaryService` (`getRoutine`) ignore `isActive`. The success reply says exactly that ("…later prompt and nudge ticks skip it; messages already queued may still be delivered…") |
| `StandupRoutineSetupService.kt` | `@EventListener createRoutine(CreateStandupRoutineEvent)`: reads `findActiveRoutinesByChannel(payload.commandChannel)` first, outside the rejection path (a failing read propagates like a failing write), then builds `Routine` under `Routine.normalizeName(payload.name)` — a name equal, ignoring case, to an active routine's normalized name in that channel is rejected with `require` ("a standup routine named '…' already exists in this channel"), which the DM rejection path reports; the check is not a constraint, so two concurrent setups can still both pass, which is why `STOP` handles several matches — + one `RoutineMember` per id (each member adopts the routine timezone), `StandupRepository.createRoutine`, then stages an `OutboundMessage.ChannelMessage` confirmation, or a rejection when building the `Routine` (input validation) throws; a failed `createRoutine` write propagates, since it left the caller's transaction rollback-only (`STANDUP_SETUP_SUBMIT`) and `eventPublisher.publishOne`. `Routine.init` (plus the `requireNotNull` on an unusable cutoff) is the only validator; its throw becomes the rejection text, escaped with `escapeMrkdwn`, and the confirmation escapes the routine name (the `<@id>` member mentions stay markup). Both replies go to the creator's DM (`chat.postMessage` with `channel = payload.creatorId`, `basicInfo` copied to that channel, publisher and idempotency key kept): a view_submission's `responseBasicInfo.channel` is `""` (`channel_not_found`), and an ephemeral in `payload.commandChannel` fails with `no_permission` when the bot is not a member there (`/standup setup` runs in any channel) — Slack treats that as a permanent per-channel failure, so the reply was lost. The DM path is the one meeting reminders and standup prompts already use |
| `StandupAnswerService.kt` | `@EventListener recordAnswer(RecordStandupAnswerEvent)` → `StandupRepository.recordAnswer` (joins the interaction transaction, so the session row lock is held until it commits), then collapses the DM prompt (`payload.notice`, when ferried) with the outcome: `RECORDED` → `SUBMITTED_NOTICE` ("Standup submitted."), `SESSION_CLOSED` → `CLOSED_NOTICE`, `SESSION_NOT_FOUND` → `SESSION_NOT_FOUND_NOTICE` — a late answer is never told "submitted"; `@EventListener @Transactional onStandupModalOpenFailed(StandupModalOpenFailedEvent)` (its own transaction, because modals now open after the caller's transaction ended and the outbox write is BEFORE_COMMIT) stages an `Ephemeral` with `recipient = null` into the originating DM channel |
| `StandupScheduler.kt` | `@Component`, `@Scheduled(fixedDelay = 60_000) tick()`: `openSessionsForToday` → `sendPendingDispatches` → `nudgeNonResponders` → `detectCutoffs`, each phase in its own `containFailure` so one failing phase no longer skips the later ones |
| `StandupSchedulingService.kt` | `@Service` owning the four phases, a `TransactionTemplate` built from the injected `PlatformTransactionManager`, and the `internal` builders `buildDmNotice` (`OutboundMessage.Approval`, buttons "Fill in standup" / "Skip", `STANDUP_PROMPT`, `routingExtras = [sessionUid, routineUid]`) and `buildNudgeNotice` (`ChannelMessage`; the creator-chosen routine name is passed through `escapeMrkdwn`, the template's own `*bold*` is not). Publishes `StandupCutoffEvent` through a plain `ApplicationEventPublisher` |
| `StandupSummaryService.kt` | `@EventListener postSummary(StandupCutoffEvent)`: one `runInTx` reads the session under its row lock (`findSessionForSummary`, the same lock `recordAnswer` takes), skips a session that is no longer `COLLECTING`, builds a `MessageContent.StandupSummary` outbox row, saves it and runs `markSessionSummarized(messageTs = "outbox:<eventId>")` — a `false` from the CAS throws so the row rolls back. An answer acknowledged as submitted is therefore in the summary, and one that waited on the lock is told the session closed. `@EventListener replaceSummaryMarkerWithSlackTs(MessagePublishSuccessEvent)` swaps the marker for the real Slack `ts`, only for an event whose `commandDetailType` is `STANDUP_SUMMARY` (and a non-blank `ts`). The summary may be several messages: the parts come from the pure `ModalTemplateBuilder.standupSummaryParts` (each part's rendered block text within `SlackBlockLimits.MESSAGE_TEXT_BUDGET`, 12,000 — Slack rejects a message near 13,200 characters with `msg_blocks_too_long` — and at most 48 members, labelled `(n/total)`), and this listener stages them as one chained row (`outboundMessagePort.toChainHead`): the first part, with the rest posted by the relay one after another. The row and the `SUMMARIZED` CAS share the transaction, and the marker is that row's `outbox:<eventId>`, so it is the first part's `ts` that replaces it (later parts are new rows with their own ids). Each member's stored answers are `boundedForSummary()` first (`SUMMARY_MEMBER_RESPONSE_CHARS` = the section budget, split across that member's responses; control characters other than line breaks and tabs are dropped, because each costs 14 bytes in the chain head's CDC update record and only without them does a full 30-member summary stay within Kafka's 1 MiB — `OutboxPayloadSizeGuardTest`); the old 18,000-character total cap is gone |

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
  `UPDATE` is keyed on that marker, but `standup_session` has no index on `summary_message_ts` (V4), so the
  `UPDATE` scans the table and, under MariaDB REPEATABLE READ, locks every row it reads — serializing each relay
  success with the answer, summary, nudge and session-create transactions that hold session rows. The listener
  therefore runs it only for a success whose `commandDetailType` (the dispatched payload's, carried on
  `MessagePublishSuccessEvent`) is `STANDUP_SUMMARY`; every part of a chained summary qualifies, only the head
  part's `eventId` matches the marker. Narrowing further (by id) would need an index migration.
- **Two outbound styles, on purpose.** Scheduler phases write outbox rows directly with
  `CommandBasicInfo.forOutbound(publisherId = userId, channel = userId)` (a tick has no request context or
  `trigger_id`). Event-driven services go through `OutboundMessageStager.stage(...)?.let {
  eventPublisher.publishOne(it) }`. Keep each path where it is.
- **`/standup list|stop` replies are channel ephemerals, unlike the setup reply.** `chat.postEphemeral` in a
  channel the bot has not joined can fail with `no_permission`, the failure that moved the setup reply to the
  creator's DM. The list and stop replies use the `/meetup list` shape instead; if they go missing in such a
  channel, apply the same DM fallback.
- **`/standup stop` takes the row lock before anything else.** `handleStandupOps` runs inside
  `StandupSlashServiceImpl.handleStandup`'s transaction (a synchronous `@EventListener`, and nothing before it
  in that transaction touches the database). MariaDB ≥ 11.6.2 fails a locking read or `UPDATE` of a row that
  another transaction committed after this transaction's first plain read with ER_CHECKREAD 1020
  (`docs/wiki/error-handling-and-validation.md`), so `STOP` reads with `lockActiveRoutinesByChannel` and then
  `deactivateRoutine`s; it must not go back to `findActiveRoutinesByChannel` (a plain read) before that `UPDATE`.
  `LIST` only reads and keeps the plain query. The role lookup is detached from this transaction for the same
  reason the reply must commit: a participating `@Transactional(readOnly = true)` failure would leave it
  rollback-only.
- Events consumed: `CreateStandupRoutineEvent`, `StandupOpsRequestEvent`, `RecordStandupAnswerEvent`, `StandupModalOpenFailedEvent`
  (lifted from domain contexts by `SlackIntentResolver` / `ApplicationMessageDispatcher`),
  `StandupCutoffEvent` (produced here), `MessagePublishSuccessEvent` (produced by `service/relay/`).
  The answer modal itself is opened by `domain/.../context/form/StandupFillContext` from the DM button.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.service.standup.*'
```
Specs under `application/src/test/kotlin/dev/notypie/application/service/standup/`:
`StandupSchedulingServiceTest`, `StandupSummaryServiceTest`, `StandupAnswerServiceTest`,
`StandupRoutineSetupServiceTest`, `StandupRoutineOpsServiceTest`, `StandupSchedulerTest`, `StandupDispatchMessageBuilderTest` (`buildDmNotice` /
`buildNudgeNotice`). MockK the `StandupRepository`, `MessageOutboxRepository`, `OutboundMessagePort` and
`OutboundMessageStager`; pass a MockK `PlatformTransactionManager` and a fixed `Clock`; assert on the
CAS calls and captured outbox rows / staged messages. Fixtures: `createNudgeCandidateSession`
(application testFixtures, same package), `createRoutineDto` / `createRoutineMemberDto` /
`createSessionDispatchDto` / `createStandupSessionDto`, `createCreateStandupRoutineEvent` and `createStandupOpsRequestEvent`,
`createCommandBasicInfo` (domain testFixtures), `createOutboxRow` / `createStubTransactionManager`
(`dev.notypie.application.outbox`), `createRoutineStopCandidate` (infrastructure testFixtures, `dev.notypie.schema`).
`StandupRoutineOpsServiceTest` drives one STOP through an outer H2 `DataSourceTransactionManager` transaction whose
role lookup fails inside a participating template (`createH2TransactionManager` / `failInsideParticipatingTx`,
`dev.notypie.application.service.meeting`), and asserts the outer commit still succeeds. The locking read itself is
pinned on H2 by infrastructure's `StandupRepositoryImplTest`; assert orchestration here.

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
- `infrastructure/repository/standup/` — `StandupRepository`, `ReadyDispatch`, `NudgeCandidateSession`,
  `RoutineStopCandidate`
- `infrastructure/repository/outbox/` — `MessageOutboxRepository`, `OutboundMessagePort`,
  `dto/MessagePublishSuccessEvent`
- `infrastructure/impl/command/slack/SlashCommandRequestBody`
- `domain/standup/` — `Routine`, `RoutineMember`, `StandupSession`, `SessionDispatch`, `RoutineDto`
- `domain/command/entity/event/` — `CreateStandupRoutineEvent`, `RecordStandupAnswerEvent`,
  `StandupModalOpenFailedEvent`, `StandupCutoffEvent`, `EventPublisher.publishOne`
- `domain/command/entity/slash/StandupCommand`, `domain/command/outbound/` (`OutboundMessage`,
  `MessageContent.StandupSummary`, `OutboundMessageStager`), `domain/command/dto/modals/ApprovalContents`
- `application/service/command/CommandExecutor`, `application/service/command/CommandRoleResolver`
  (stop authorization), `domain/command/authorization/CommandPermission`, `application/common/` (`IdempotencyCreator`, `runInTx`, `detachedTemplate`),
  `application/configurations/AppConfig`

### External
Spring scheduling / transaction / context events, kotlin-logging.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
