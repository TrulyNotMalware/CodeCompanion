<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-10-07 -->

# test/kotlin/dev/notypie/application/service/standup

## Purpose
Specs for the standup lane: routine creation from the setup modal, `/standup list|stop`, the four scheduler phases (open today's
sessions, send DM prompts, detect cutoffs, nudge non-responders), answer recording plus the modal-open
fallback, the summary post at cutoff, and the DM/nudge message builders. All effects are asserted on
`OutboundMessage` values captured at `OutboundMessagePort.toRow` or `OutboundMessageStager.stage`.

## Key Files
| File | Description |
|------|-------------|
| `StandupAnswerServiceTest.kt` | Plain Kotest `BehaviorSpec` + MockK. `recordAnswer(RecordStandupAnswerEvent)` (`createRecordStandupAnswerEvent`) → `repo.recordAnswer(sessionUid, userId = "U_STANDUP", responses = ["Done", "Next"], submittedAt = clock instant)` once; `RECORDED` → the ferried notice is updated to `SUBMITTED_NOTICE` with `CommandBasicInfo.forOutbound(publisherId, channel = D_NOTICE, idempotencyKey)` and published once; `SESSION_CLOSED` → `CLOSED_NOTICE`, never `SUBMITTED_NOTICE`; `SESSION_NOT_FOUND` → `SESSION_NOT_FOUND_NOTICE`; no notice ferried → nothing staged; `onStandupModalOpenFailed` → stages `Ephemeral(target = D_STANDUP, recipient = null)` with the exact "Couldn't open the standup form. _Tip: re-click the *Fill in standup* button…_" text and `CommandBasicInfo.forOutbound(appId, publisherId, channel, idempotencyKey)`, and the published `DefaultEventQueue` polls non-null. |
| `StandupDispatchMessageBuilderTest.kt` | Plain Kotest `BehaviorSpec`, no mocks. `buildDmNotice` → `Approval` with `recipient = UserRef("U_TARGET")`, `routingExtras = [sessionUid, routineUid]`, `approval.idempotencyKey == sessionUid`, `STANDUP_PROMPT`, headline `Daily Standup — 2026-05-04`, buttons `Fill in standup`/`Skip`, target = command channel. `buildNudgeNotice` with cutoff 01:00Z in `Asia/Seoul` → text contains `*Daily Standup*`, `closes at 10:00`, `haven't responded yet`, `*Fill in standup*`; headline `Standup reminder`, `STANDUP_PROMPT`, plain `ChannelMessage`. `buildNudgeNotice` with a disguised link and `&` in the routine name → escaped inside the bold, `*Fill in standup* button` kept as markup. |
| `StandupRoutineOpsServiceTest.kt` | Plain Kotest `BehaviorSpec` + MockK, events from `createStandupOpsRequestEvent`. Every reply is the captured `Ephemeral` to `TEST_CHANNEL_ID` with `recipient = TEST_USER_ID`. `LIST` with two routines → `STANDUP_ROUTINE_LIST`, header `Active standup routines in <#C…> (2):`, the exact `• *Daily Sync* — 09:30 Asia/Seoul, Mon/Wed/Fri, cutoff +90m, 2 member(s), summary → <#C_SUMMARY>, created by <@U_CREATOR>` line (weekdays given out of order), `Retro <team>` escaped, `1 member(s)`, one publish; empty → the `/standup setup` pointer. `STOP`: creator with a differently-cased name → `deactivateRoutine` once, no role lookup, `STANDUP_ROUTINE_STOP` and the exact success text; non-creator `ADMIN` → allowed; non-creator `USER` → exact denial text, no `deactivateRoutine`; unknown name with `<retro>` → escaped "No active standup routine named …" text; `deactivateRoutine` → `false` → "already stopped"; basic info from `C_ELSEWHERE` → only that channel's routines are read and the reply targets it |
| `StandupRoutineSetupServiceTest.kt` | Plain Kotest `BehaviorSpec` + MockK. Valid `CreateStandupRoutineEvent` → captured `Routine` has name/creator/command/summary channels, `cutoffOffset = 90 min`, `routineTimezone = UTC`, members `{U_ALICE, U_BOB}` each adopting the routine zone; confirmation `ChannelMessage` (no thread) typed `STANDUP_SETUP_SUBMIT` whose markdown contains `Daily Standup` and `created`, targeted at and `basicInfo.channel ==` the creator `U_CREATOR` (not `C_COMMAND`, and not the event's `responseBasicInfo.channel` `""` as in production), publisher and idempotency key kept, `publishEvent` once. Empty `questions` → no `createRoutine`, error message containing `Couldn't create the standup routine`, still published once, also to the creator's DM. A routine named `<https://evil.example|Fill in standup> & co` → the confirmation carries it escaped inside the bold and keeps `<@U_ALICE>`. A valid routine whose `createRoutine` throws → the exception propagates and nothing is staged (only building the `Routine`, i.e. input validation, is caught). `cutoffMinutes = null` (unparsable) → no `createRoutine`, reply names "whole number of minutes between 1 and 1440"; `1441` → rejected by `Routine` validation, nothing persisted. |
| `StandupSchedulingServiceTest.kt` | Plain Kotest `BehaviorSpec` + MockK, 723 lines. `openSessionsForToday`: Monday routine with Seoul and LA members → `cutoffAt` = LA 10:00 + 1 h (latest member trigger), per-member `dmTriggerAt` in each zone; weekday mismatch → no `findSession`/`createSession`; existing session → skipped; `DataIntegrityViolationException` with `findSession` `returnsMany [null, mock]` → swallowed, two lookups; DIVE with no session, or a `RuntimeException`, on the first of two routines → contained to that routine and the second routine's session is still created; a routine whose `cutoffOffset` overflows `Instant` → skipped, the healthy one opens; `InterruptedException` from `createSession` → rethrown with the interrupt flag set, no further routine tried. `sendPendingDispatches`: claim won → `claimDispatch`/`save`/`markDispatchSent` once each with the same token in both slots, `Approval` DM to `U_A` (`STANDUP_PROMPT`, `subTitle = ""`, routing extras); claim lost → nothing; `toRow` throws after the claim → `getTransaction` precedes `claimDispatch`, `setRollbackOnly`, no `markDispatchSent`, no save, and `recordDispatchFailure(42L, reason contains "Slack API error", now)`; a dispatch with a stored `failureReason` whose session reached cutoff → `markDispatchSkipped(43L, "enqueue failed until cutoff: connection timeout", now)`; nothing pending → no `listActiveRoutines`; unknown routine → no claim, `markDispatchSkipped(99L, "routine inactive", now)`; an inactive routine's stale row ahead of an active one → the stale row is skipped and the active member is still DM'd in the same tick; a `SUMMARIZED` session and one whose `cutoffAt` is now → both skipped with "session closed before the DM was sent", no claim, no save. Real H2 `DataSourceTransactionManager` with probe tables standing in for the dispatch and nudge rows: an outbox write that fails after both claims leaves the dispatch `PENDING` and the session un-nudged; a successful one commits `SENT` and nudged together with two outbox saves. `detectCutoffs` → `ApplicationEventPublisher.publishEvent(StandupCutoffEvent(9L, …))`; the listener throwing for the first of two sessions → contained, the second is still published. `nudgeNonResponders`: sent `{U_A,U_B,U_C}`, answered `{U_C}` → `claimNudge(7L)` once, two saves, exact `⏰ Standup for *Daily Standup* closes at 12:10 — …` `ChannelMessage` per non-responder with `basicInfo.publisherId == channel == user`; all answered → no claim; claim lost → no save; `offsetMinutes = 0` → zero repository work; `offsetMinutes = 30` → captured window `[now, now + 30m]`. |
| `StandupSchedulerTest.kt` | Plain Kotest `BehaviorSpec` + MockK on `StandupScheduler.tick`: the open phase throwing `DateTimeException` → the other three phases still run once; every phase throwing → each attempted once, nothing escapes; an `InterruptedException` → rethrown with the flag restored and the later phases skipped; an `Error` → propagates. |
| `StandupSummaryServiceTest.kt` | Plain Kotest `BehaviorSpec` + MockK. `boundedForSummary` drops control characters but keeps `\n` and `\t`. Cutoff for a collecting session (`findSessionForSummary`, never `findSession`) → `toRow` with the exact `ChannelMessage(C_SUMMARY, MessageContent.StandupSummary(routineName, sessionDate, members, answers, questions))`, `save(summaryRow)`, `markSessionSummarized(7L, "outbox:EVT-SUMMARY")`; session missing → no save, no CAS; `verifyOrder` pins `getTransaction` → `findSessionForSummary` → `save` → `markSessionSummarized` → `commit`; a session the locked read finds `SUMMARIZED` → no routine read, no row, no save, no CAS; on a real H2 `DataSourceTransactionManager` with a probe table that `save` writes through `JdbcTemplate`: the collecting-session case commits one row, and CAS returns false → the row written before the rejected CAS rolls back (0 rows); `replaceSummaryMarkerWithSlackTs(MessagePublishSuccessEvent)` with `STANDUP_SUMMARY` → `replaceSummaryMessageTs(currentMessageTs = "outbox:$eventId", messageTs)`; with `SIMPLE_TEXT` → no `replaceSummaryMessageTs` call (fails without the type check). A full routine (30 members, eight ~200-character Korean questions, 3,000-character Korean stored answers) → one chained row (`captureChains`) whose parts, each rendered through a real `ModalTemplateBuilder` stays within 50 blocks and `MESSAGE_TEXT_BUDGET`, every member appears once in routine order, parts are labelled `(n/total)`, exactly one row is saved, and the CAS uses that row's marker; a 5,000-character answer is stored bounded to `SUMMARY_MEMBER_RESPONSE_CHARS` with an ellipsis |

## For AI Agents

### Working In This Directory
- The scheduling clock is `Clock.fixed(2026-05-04T12:00 Asia/Seoul, ZoneOffset.UTC)` and `today` is derived
  with `LocalDate.ofInstant(nowInstant, seoul)`; the nudge text `closes at 12:10` is `now + 600 s` rendered
  in the routine zone. Moving `nowInstant` shifts every expected time in the file.
- Claim-token CAS is asserted by capturing `claimDispatch(claimToken = capture(a))` and
  `markDispatchSent(claimToken = capture(b))` and comparing `a.captured == b.captured`; the same idiom is in
  `meeting` and `cve/ai`.
- `stubPort()` answers `toRow` with `createOutboxRow(UUID)`; `stubTransactionManager()` stubs
  `getTransaction`/`commit`/`rollback` with `just Runs`. Both are file-local copies, not shared fixtures. A stub
  manager cannot show a rollback, so the summary commit/rollback cases use `createH2DataSource` +
  `createH2TransactionManager` from the meeting testFixtures instead.
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
`command/CommandDomainInputCreator.kt` (`createCommandBasicInfo`, `createCreateStandupRoutineEvent`,
`createStandupOpsRequestEvent`) and
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
