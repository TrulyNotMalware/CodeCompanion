<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# test/kotlin/dev/notypie/application/service/standup

## Purpose
Specs for the standup lane: routine creation from the setup modal, the four scheduler phases (open today's
sessions, send DM prompts, detect cutoffs, nudge non-responders), answer recording plus the modal-open
fallback, the summary post at cutoff, and the DM/nudge message builders. All effects are asserted on
`OutboundMessage` values captured at `OutboundMessagePort.toRow` or `OutboundMessageStager.stage`.

## Key Files
| File | Description |
|------|-------------|
| `StandupAnswerServiceTest.kt` | Plain Kotest `BehaviorSpec` + MockK. `recordAnswer(RecordStandupAnswerEvent)` → `repo.recordAnswer(sessionUid, userId = "U_STANDUP", responses = ["Done", "Next"], submittedAt = clock instant)` once; `onStandupModalOpenFailed` → stages `Ephemeral(target = D_STANDUP, recipient = null)` with the exact "Couldn't open the standup form. _Tip: re-click the *Fill in standup* button…_" text and `CommandBasicInfo.forOutbound(appId, publisherId, channel, idempotencyKey)`, and the published `DefaultEventQueue` polls non-null. |
| `StandupDispatchMessageBuilderTest.kt` | Plain Kotest `BehaviorSpec`, no mocks. `buildDmNotice` → `Approval` with `recipient = UserRef("U_TARGET")`, `routingExtras = [sessionUid, routineUid]`, `approval.idempotencyKey == sessionUid`, `STANDUP_PROMPT`, headline `Daily Standup — 2026-05-04`, buttons `Fill in standup`/`Skip`, target = command channel. `buildNudgeNotice` with cutoff 01:00Z in `Asia/Seoul` → text contains `*Daily Standup*`, `closes at 10:00`, `haven't responded yet`, `*Fill in standup*`; headline `Standup reminder`, `STANDUP_PROMPT`, plain `ChannelMessage`. |
| `StandupRoutineSetupServiceTest.kt` | Plain Kotest `BehaviorSpec` + MockK. Valid `CreateStandupRoutineEvent` → captured `Routine` has name/creator/command/summary channels, `cutoffOffset = 90 min`, `routineTimezone = UTC`, members `{U_ALICE, U_BOB}` each adopting the routine zone; confirmation `Ephemeral` typed `STANDUP_SETUP_SUBMIT` whose markdown contains `Daily Standup` and `created`, `basicInfo.channel == C_COMMAND`, `publishEvent` once. Empty `questions` → no `createRoutine`, error ephemeral containing `Couldn't create the standup routine`, still published once. |
| `StandupSchedulingServiceTest.kt` | Plain Kotest `BehaviorSpec` + MockK, 723 lines. `openSessionsForToday`: Monday routine with Seoul and LA members → `cutoffAt` = LA 10:00 + 1 h (latest member trigger), per-member `dmTriggerAt` in each zone; weekday mismatch → no `findSession`/`createSession`; existing session → skipped; `DataIntegrityViolationException` with `findSession` `returnsMany [null, mock]` → swallowed, two lookups; DIVE with no session → propagates; `RuntimeException` → propagates. `sendPendingDispatches`: claim won → `claimDispatch`/`save`/`markDispatchSent` once each with the same token in both slots, `Approval` DM to `U_A` (`STANDUP_PROMPT`, `subTitle = ""`, routing extras); claim lost → nothing; `toRow` throws → `markDispatchFailed(reason contains "Slack API error")`, no save; nothing pending → no `listActiveRoutines`; unknown routine → no claim. `detectCutoffs` → `ApplicationEventPublisher.publishEvent(StandupCutoffEvent(9L, …))`. `nudgeNonResponders`: sent `{U_A,U_B,U_C}`, answered `{U_C}` → `claimNudge(7L)` once, two saves, exact `⏰ Standup for *Daily Standup* closes at 12:10 — …` `ChannelMessage` per non-responder with `basicInfo.publisherId == channel == user`; all answered → no claim; claim lost → no save; `offsetMinutes = 0` → zero repository work; `offsetMinutes = 30` → captured window `[now, now + 30m]`. |
| `StandupSummaryServiceTest.kt` | Plain Kotest `BehaviorSpec` + MockK. Cutoff for a collecting session → `toRow` with the exact `ChannelMessage(C_SUMMARY, MessageContent.StandupSummary(routineName, sessionDate, members, answers, questions))`, `save(summaryRow)`, `markSessionSummarized(7L, "outbox:EVT-SUMMARY")`; session missing → no save, no CAS; CAS returns false → only the attempt is asserted (rollback is via the relaxed `TransactionStatus`); `replaceSummaryMarkerWithSlackTs(MessagePublishSuccessEvent)` → `replaceSummaryMessageTs(currentMessageTs = "outbox:$eventId", messageTs)`. |

## For AI Agents

### Working In This Directory
- The scheduling clock is `Clock.fixed(2026-05-04T12:00 Asia/Seoul, ZoneOffset.UTC)` and `today` is derived
  with `LocalDate.ofInstant(nowInstant, seoul)`; the nudge text `closes at 12:10` is `now + 600 s` rendered
  in the routine zone. Moving `nowInstant` shifts every expected time in the file.
- Claim-token CAS is asserted by capturing `claimDispatch(claimToken = capture(a))` and
  `markDispatchSent(claimToken = capture(b))` and comparing `a.captured == b.captured`; the same idiom is in
  `meeting` and `cve/ai`.
- `stubPort()` answers `toRow` with `createOutboxRow(UUID)`; `stubTransactionManager()` stubs
  `getTransaction`/`commit`/`rollback` with `just Runs`. Both are file-local copies, not shared fixtures.
- Kotest accumulates `verify` counts across `when` blocks that share mocks, so the scheduling and summary
  specs create fresh mocks inside every `when`. The propagation cases use `try`/`catch` + `AssertionError`
  instead of `shouldThrow`.
- `buildDmNotice`/`buildNudgeNotice` are `internal fun` in `StandupSchedulingService.kt`; there is no
  `StandupDispatchMessageBuilder` class despite the spec name.
- `ReadyDispatch`, `StandupCutoffEvent`, `RecordStandupAnswerEvent`, and `StandupModalOpenFailedEvent` are
  built inline (`readyDispatchOf` helper for the first); only routine/session/dispatch DTOs come from fixtures.
- The modal-open-failed ephemeral deliberately leaves `recipient = null` and carries the user id in
  `basicInfo.publisherId`, because `chat.postEphemeral` needs channel and user as separate fields.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.service.standup.*'
```
Fixtures used: `application` testFixtures `outbox/OutboxTestFixtures.kt` (`createOutboxRow`) and
`service/standup/NudgeCandidateSessionCreator.kt` (`createNudgeCandidateSession`); `domain` testFixtures
`command/CommandDomainInputCreator.kt` (`createCommandBasicInfo`, `createCreateStandupRoutineEvent`) and
`standup/StandupTestFixtures.kt` (`createRoutineDto`, `createRoutineMemberDto`, `createStandupSessionDto`,
`createSessionDispatchDto`). The domain entity builders `createRoutine`/`createStandupSession` are not used
here — the services consume DTOs.

### Common Patterns
- `every { repo.createSession(session = capture(slot)) } answers { firstArg() }` echoes the entity so the
  spec can assert on `dispatchSnapshot()` / `memberSnapshot()`.
- Stager stubs return a relaxed `SendSlackMessageEvent`; the setup spec asserts the message with
  `match { message is Ephemeral && … }` rather than equality because the event carries a random key.

## Dependencies

### Internal
- `application/service/standup/StandupAnswerService.kt`, `StandupRoutineSetupService.kt`,
  `StandupSchedulingService.kt`, `StandupSummaryService.kt`, `application/configurations/AppConfig.Standup`
- `domain/standup/entity/Routine`, `StandupSession`, `domain/command/entity/event/StandupCutoffEvent`,
  `RecordStandupAnswerEvent`, `StandupModalOpenFailedEvent`, `domain/command/outbound/*`
- `infrastructure/repository/standup/StandupRepository`, `ReadyDispatch`, `NudgeCandidateSession`,
  `repository/outbox/MessageOutboxRepository`, `OutboundMessagePort`,
  `impl/command/event/SendSlackMessageEvent`

### External
MockK (`slot`, `returnsMany`), Kotest, Spring `PlatformTransactionManager`, `ApplicationEventPublisher`,
`DataIntegrityViolationException`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
