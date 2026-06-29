# CodeCompanion — Roadmap Handoff

_Last updated: 2026-06-18 · verified against working tree (commit `154f340`)_

This document captures the next-phase roadmap so it survives session compaction.

> **NEXT SESSION** — **Phase 3 (Standup Bot) is now fully complete.** #10/#11/#12/#13 are all implemented and committed (`b966f96 feat : Standup`). The working tree is clean. The next work is **Phase 4 — Optional / Incremental** (see table below); #14 meeting reminders is the natural starting point (few-hour reuse of the standup scheduler).
>
> _The old "#11 and #13 remain" pickup points below are kept for historical context but are **DONE** — see the ✅ markers._

---

## Project Overview

**CodeCompanion** — Kotlin / Spring Boot 4 / Java 25 Slack bot for personal side projects.

- Multi-module Gradle: `domain` / `application` / `infrastructure` (DDD layered)
- Stack: Kotlin 2.3.20, Kotest + MockK, Slack Java SDK 1.48, MariaDB + JPA + Flyway, Kafka outbox (with Debezium CDC option), Jackson 3, kotlin-logging
- Single-tenant, single-machine personal-use bot

---

## Current State Snapshot

### Phase status

| Phase | Status |
|---|---|
| Phase 1 — Foundation Hardening | ✅ done (#1-#5) |
| Phase 2 — Outbox Model + AppMention | ✅ done (#6-#9) |
| Phase 3 — Standup Bot | ✅ done (#10-#13) |
| Phase 4 — Optional / Incremental | 🟡 #14 done · #15/#16 next |

### Shipped features
- `/meetup` create / approve / decline / cancel (Phase 1)
- `/meetup list` with inline cancel button on host's own meetings (Phase 1 #5)
- Outbox relay with `IN_PROGRESS` claim/lock + stuck-recovery (Phase 2 #6)
- `OutboxHealthIndicator` reporting PENDING + IN_PROGRESS lag (Phase 2 #6 extends Phase 1 #3)
- `@bot help` and `@bot status` AppMention replies (Phase 2 #7, #8)
- Outbox payload `schemaVersion` with reader-side dispatch (Phase 2 #9)
- **Standup domain model** — `Routine`, `StandupSession`, `SessionDispatch`, `StandupAnswer` + JPA schemas + `V4__add_standup_tables.sql` (Phase 3 #10)
- **Standup scheduler** — 3-phase tick (open today's sessions / send DM prompts / detect cutoffs) with per-claim tokens, transaction-template rollback, per-member timezone, and `cutoffAt = max(member trigger) + offset` (Phase 3 #12)
- **Standup DM modal collection** — "Fill in standup" button → `OpenStandupModal` intent → `resolveOpenStandupModal` → `views.open`; submission → `StandupAnswerSubmissionContext` → `RecordStandupAnswer` → `StandupAnswerService` → `standupRepository.recordAnswer(...)` upsert on `standup_answer` (Phase 3 #11)
- **Standup channel summary** — `detectCutoffs()` publishes `StandupCutoffEvent`; `StandupSummaryService` aggregates answers, posts to `routine.summaryChannel`, and marks the session `SUMMARIZED` via CAS (`markSessionSummarized`) for restart-safe idempotency (Phase 3 #13)
- **Meeting reminders** — 5-min / 15-min pre-meeting DMs to attending participants, via a `meeting_reminder` CAS claim/lock table + scheduler mirroring the standup pattern, idempotent on `(meeting_id, offset_minutes)` (Phase 4 #14)
- **`/standup setup`** — slash command opens a modal (name, questions, members, summary channel, weekdays, trigger time, cutoff, timezone) → persists a `Routine` via `createRoutine` (closes the SQL-only gap; new endpoint `POST /api/slash/standup`)
- **Meeting reschedule** — inline *Reschedule* button on `/meetup list` host rows → date/time modal → atomic host-only `rescheduleMeeting` UPDATE + participant re-notification + `meeting_reminder` delete-and-recreate
- **Standup non-responder nudge** — scheduler phase DMs members who got the prompt but haven't answered, once per session (`standup_session.nudged_at` CAS gate), within `[cutoff - nudgeOffset, cutoff)` (config `slack.app.standup.nudge.offset-minutes`, default 30; migration `V6`)
- **Daily agenda DM** — once-per-day morning DM of each user's attending meetings for today; `agenda_dispatch` per-date `INSERT IGNORE` claim + time-of-day gate (config `slack.app.meeting.agenda.send-at`/`timezone`/`enabled`; migration `V7`)

### Session notes (2026-06-18 — refactor + #14 + E2E)
- **Refactor.md applied:** Rule 1 — moved single-call service `@Transactional` (StandupAnswerService.recordAnswer, SlackMessageRelayServiceImpl.saveOutboxMessages/updateOutboxMessageStatus) down to the repository layer; the 3 multi-step service boundaries (handleMeeting/handleEvent/handleInteraction) intentionally kept at service level. Rule 3 — merged `UserRepository`/`TeamRepository` markers into one file. Rule 4 — null-coalescing → scope functions in `OutboxHealthIndicator`/`OpsStatusService`. Added the missing `SlackMessageRelayServiceImplTest`.
- **Bug fixed (pre-existing, found during CDC E2E):** `DebeziumLogTailingProcessor`'s Kafka consumer config pointed at a non-existent class `dev.notypie.application.service.relay.dto.Envelope` (stray `.dto.`); the class is `dev.notypie.application.service.relay.Envelope`. This broke **CDC-mode startup** (consumer construction `ClassNotFoundException`). Fixed.
- **Bug fixed (latent, found booting the `real` profile):** `StandupSessionSchema` had both `dispatches` and `answers` as `List` (bags), and `findBySessionUid` / `findByRoutineUidAndSessionDate` / the new `findCollectingForNudge` all `JOIN FETCH` both → Hibernate `MultipleBagFetchException` at query execution. This would have broken standup answer recording, summary, and nudge against a real DB (mock-based unit tests didn't catch it). Fixed by making `dispatches` a `Set` (one bag + one set is allowed). Verified: `real` profile boots and the standup scheduler ticks with no exception.
- **New-feature run profile:** `application-real.yaml` (`real` profile) — POLLING + APPLICATION_EVENT against the local orbstack MariaDB, Slack token/secret via env. See `RealTestSetup.md` for the Slack-app config checklist (slash `/api/slash/meet` + `/api/slash/standup`, interactivity `/api/slack/interaction`, events `/api/slack/events`) + tunnel guidance.
- **E2E (orbstack, `local` profile):** outbox → Debezium connector (`~/infra/debezium/connectors/codecompanion-outbox.json`) → Kafka topic `cdc.code_companion.outbox_message` verified end to end; schema/JPA validated on real MariaDB; scheduler confirmed invoking the reminder materialize query each tick. Note: a clean live reminder-fire run needs a machine that does not sleep (host clock-leaps starved the Hikari pool and froze the consumer mid-session). Minor: the `V5` migration declares `updated_at DATETIME NULL` while Hibernate `ddl-auto` generates it `NOT NULL` (both fine since `@UpdateTimestamp` always populates it).

### Skeletons present, not wired
- AppMention `@bot help` / `@bot status` are wired; the parser also has `notice` and `approval` paths from before.
- History domain — repository + mapper exist, no persistence wired.
- User/Team domain — schema present, no business logic.

> _Resolved since 2026-05-01: `StandupFillContext` is fully wired (now emits `CommandIntent.OpenStandupModal`), `SlackIntentResolver` opens the modal, and `detectCutoffs()` posts a real summary. The "#11/#13 next" notes below are historical — both shipped._

### Recent commits (most-recent first)
- `154f340` chore : update dependencies
- `b966f96` feat : Standup (Phase 3 #10/#11/#12/#13 — domain, scheduler, DM modal collection, channel summary)
- `6319531` feat: version outbox payload schema with reader-side dispatch (Phase 2 #9)
- `90673d6` feat: route CANCEL_MEETING through CommandIntent + context scaffolding (Phase 1 #5)
- `df7609e` feat: add atomic markMeetingCanceled with host-only authorization
- `5653a67` feat: add OutboxHealthIndicator reporting pending lag and stuck rows
- `8726536` feat: preserve teamId on SlackCommandData across all entry points

> **Working tree is clean** (only `Handoff.md` + `Refactor.md` are untracked). All Phase 2 + Phase 3 work that this doc previously listed as "uncommitted" has landed — Phase 2 #6/#7/#8 across earlier commits, and the whole standup feature (#10–#13) in `b966f96`.

### Known gaps / intentional skips
- `application-dev.yaml` has hardcoded credentials — **intentional** for local-only use, NOT a security review concern.
- Multi-workspace tenancy — overkill for personal use; mitigated by `teamId` preservation.
- Recurring meetings (RRULE), Spring Modulith migration, task tracker — all intentionally deferred.

---

## Phase 1 — Foundation Hardening · ✅ done

| # | Item | Status |
|---|---|---|
| 1 | Slack signature verification + retry header dedup | ✅ |
| 2 | Narrow prod actuator exposure | ✅ |
| 3 | Custom health indicators (outbox) | ✅ |
| 4 | Preserve `teamId` in `SlackCommandData` | ✅ |
| 5 | Meeting cancel via `/meetup list` button | ✅ |

---

## Phase 2 — Outbox Model + AppMention · ✅ done

| # | Item | Status |
|---|---|---|
| 6 | Outbox `IN_PROGRESS` state + claim/lock + stuck recovery | ✅ |
| 7 | `@bot help` AppMention reply | ✅ |
| 8 | `@bot status` AppMention reply (outbox lag + in-flight) | ✅ |
| 9 | Event schema versioning (`OutboxSchemaVersion.CURRENT`) | ✅ |

---

## Phase 3 — Standup Bot · ✅ done

| # | Item | Status | Notes |
|---|---|---|---|
| 10 | Standup domain model | ✅ | `Routine`, `StandupSession`, `SessionDispatch`, `StandupAnswer` + JPA schemas + `V4__add_standup_tables.sql` |
| 11 | DM modal collection | ✅ | Fill button → `OpenStandupModal` → `views.open`; submission → `RecordStandupAnswer` → `recordAnswer(...)` upsert on `standup_answer`. |
| 12 | Scheduling infrastructure | ✅ | Per-member timezone, claim tokens, atomic CAS, transaction-template rollback, stuck recovery. |
| 13 | Channel summary post | ✅ | `detectCutoffs()` → `StandupCutoffEvent` → `StandupSummaryService` aggregates + posts to `routine.summaryChannel`, CAS `markSessionSummarized` for idempotency. |

---

## Phase 4 — Optional / Incremental

| # | Item | Effort | Notes |
|---|---|---|---|
| 14 | Meeting reminders (5-min / 15-min) | ✅ done | `meeting_reminder` table (CAS claim/lock mirroring standup), `MeetingReminderSchedulingService` (materialize + send), `MeetingReminderScheduler` (@Scheduled 60s), `MeetingReminderMessageBuilder`, migration `V5__add_meeting_reminder_table.sql`. Offsets configurable via `slack.app.meeting.reminder.offsets-minutes` (default `15,5`). 12 unit tests. |
| 15 | Prometheus metrics | M | Slack API failure rate, retry exhaustion, outbox throughput, in-flight gauge. |
| 16 | History domain persistence | M | Wire up the dormant `HistoryRepository`. |

---

## Cross-cutting Concerns

1. **Authorization / permissions.** Atomic WHERE-clause + UI-side defense-in-depth pattern is established (see `markMeetingCanceled`).
2. **Slack retry / idempotency.** Folded into Phase 1 #1's `SlackRetryDeduplicator`.
3. **Timezone strategy.** Decision A (per-user `ZoneId`) is in production for standup. Phase 4 #14 reminders inherit this.
4. **Slack API failure observability.** Phase 4 #15 picks this up.

---

## Codex review history (Phase 3 #12)

Phase 3 #12 went through 4 Codex review rounds. All 8 distinct findings are resolved in the working tree. Artifacts at `.omc/artifacts/ask/codex-*-2026-05-01T*.md`.

| Round | Finding | Severity | Resolution |
|---|---|---|---|
| 1 | DM never sent (BEFORE_COMMIT listener needs an active tx) | 🔴 | TransactionTemplate + direct `outboxRepository.save(...)` |
| 1 | `resetStuckSending` keys off unmapped column | 🔴 | Added `updated_at` (`@UpdateTimestamp`) + explicit `SET updated_at = CURRENT_TIMESTAMP` in every transition |
| 1 | Timezone — `sendPendingDispatches` scoped to "today's" session in routine zone | 🔴 | `findPendingDispatchesBefore` queries by absolute UTC; `ReadyDispatch` carries session/routine context |
| 1 | `createSession` catch-all swallows real bugs | 🟡 | `catch (DataIntegrityViolationException)` + re-query confirms race; rethrow otherwise |
| 1 | `markDispatchSent/Failed` discard return values | 🟡 | Return `Boolean`; service rolls back on race |
| 1 | `@EnableScheduling` only on `PoolingPublisherConfig` | 🟡 | New `SchedulingConfig` is unconditional |
| 1 | KDoc says `views.open`, actual is `chat.postMessage` | 🟢 | KDoc updated |
| 2 | Cutoff fires before LA member receives DM | 🔴 | `cutoffAt = max(member dmTriggerAt) + cutoffOffset` |
| 3 | `markDispatchFailed` could clobber another tick's claim | 🟡 | Per-claim `claim_token` (UUID) on every CAS predicate |
| 4 | No DB migration for `claim_token` (prod has `ddl-auto: none`) | 🔴 | `V4__add_standup_tables.sql` covers all 5 standup tables incl. `claim_token` |
| 4 | Test uses `claimToken = any()` — doesn't prove threading | 🟢 | Slot-capture: assert `sentToken.captured shouldBe claimedToken.captured` |

---

## Pickup Point — Phase 3 #11 (DM modal collection) · ✅ SHIPPED in `b966f96`

> **HISTORICAL — this work is done.** The plan below was the original spec; the actual implementation matches it closely. As-built: `StandupFillContext` emits `CommandIntent.OpenStandupModal`; `SlackIntentResolver.resolveOpenStandupModal` loads the routine via the injected `StandupRepository` and opens the modal; `StandupAnswerSubmissionContext` emits `RecordStandupAnswer`; `StandupAnswerService` persists via `standupRepository.recordAnswer(...)`. Modal IDs live in `InteractiveIds.kt` (`StandupModalIds`), and answers are stored as a cascade collection on `StandupSessionSchema` (no separate `JpaStandupAnswerRepository`). Kept below for reference only.

> **Why #11 first:** the standup scheduler (#12) sends a DM with a "Fill in standup" button, but clicking that button currently goes nowhere — `SlackIntentResolver` has `is CommandIntent.StandupFill -> null`. #11 wires the click → `views.open` → submission → `standup_answer` row. **#13 has nothing to summarize until #11 ships.**

### What ships in #11

1. Click on "Fill in standup" → bot opens a modal with the routine's questions as multi-line `plain_text_input` fields.
2. User submits → answers persisted to `standup_answer` (one row per member per session).
3. Re-submit overrides prior answer (the entity already coalesces by `(session_id, user_id)`).

### Architecture mirror

The decline-reason modal flow is the closest existing analog. Mirror it exactly:

```
StandupFillContext.handleInteraction
  → emits CommandIntent.OpenStandupModal(triggerId, sessionUid, routineUid, questions, ...)
SlackIntentResolver.resolve
  → builds OpenViewEvent (synchronous dispatch — trigger_id expires in 3s)
ApplicationMessageDispatcher
  → calls Slack views.open

[user fills modal, hits Submit]

SlackInteractionRequestParser.toInteractionPayloads(viewSubmission)
  → routes to type=STANDUP_ANSWER_SUBMIT (new CommandDetailType)
StandupAnswerSubmissionContext.handleInteraction
  → emits CommandIntent.RecordStandupAnswer(sessionUid, userId, responses)
SlackIntentResolver
  → builds RecordStandupAnswerEvent (internal)
StandupAnswerService.@EventListener
  → standupRepository.recordAnswer(...) → INSERT/UPDATE on standup_answer
```

### File-by-file work plan (~14 files)

**Domain (`domain/src/main/kotlin/dev/notypie/domain/...`)**

1. `command/entity/CommandType.kt` — add `STANDUP_ANSWER_SUBMIT` to `CommandDetailType`. Add `createContext()` branch returning `StandupAnswerSubmissionContext`.

2. `command/intent/CommandIntent.kt` — add two intents:
   - `OpenStandupModal(triggerId: String, sessionUid: UUID, routineUid: UUID, routineName: String, sessionDate: LocalDate, questions: List<String>, requesterId: String, noticeChannel: String, noticeMessageTs: String, commandDetailType = STANDUP_FILL)` — mirrors `OpenDeclineReasonModal` shape.
   - `RecordStandupAnswer(sessionUid: UUID, userId: String, responses: List<String>, commandDetailType = STANDUP_ANSWER_SUBMIT)`.
   - **Existing `CommandIntent.StandupFill` becomes a step in the chain** — `StandupFillContext` keeps emitting it, but `SlackIntentResolver` will need a real handler now (see step 7 below). Or refactor: `StandupFillContext` emits `OpenStandupModal` directly. Either works; the simpler version skips the extra hop.

3. `command/entity/event/Event.kt` — add `RecordStandupAnswerPayload` + `RecordStandupAnswerEvent` (internal, async). Mirror `UpdateMeetingAttendanceEvent`. Plus `StandupModalOpenFailedEvent` for the views.open fallback (optional — see decline-reason flow).

4. `command/entity/context/form/StandupFillContext.kt` (existing stub) — replace the placeholder. Currently parses `routingExtras[0]=sessionUid, [1]=routineUid` and emits `CommandIntent.StandupFill`. Change it to look up the routine's questions (need a way to load — see point 6) and emit `CommandIntent.OpenStandupModal` with `triggerId = interactionPayload.triggerId`. Carry `noticeChannel = channel.id` and `noticeMessageTs = container.messageTs` so the submission handler can `chat.update` the original DM into a "submitted ✓" notice.

5. `command/entity/context/form/StandupAnswerSubmissionContext.kt` (new) — mirror `DeclineReasonSubmissionContext`. In `handleInteraction(viewSubmission)`:
   - Read `view.state.values` keyed by per-question block_ids (decided in step 8) to extract responses in order.
   - Read `private_metadata` for `sessionUid` + `userId` (the parser threads them through `routingExtras`).
   - Emit `CommandIntent.RecordStandupAnswer(sessionUid, userId, responses)`.
   - Emit `CommandIntent.UpdateNoticeMessage(channel, messageTs, mkdMessage = "Standup submitted ✓")` to collapse the DM (mirrors decline-reason flow).

**Domain — question loading (cross-cutting)**

6. The `StandupFillContext` runs in domain — it cannot reach the repository directly. Two options:
   - **A (recommended):** the scheduler-sent DM message-text already carries `sessionUid` + `routineUid`. The `OpenStandupModal` intent doesn't need `questions` at construct time — it carries `routineUid`, and the resolver/event-builder layer (infrastructure side) is where we'll load the routine and inject questions into the view JSON. This keeps the domain layer pure.
   - **B:** route through an event listener (similar to `GetMeetingListEvent`) that loads the routine and re-publishes an `OpenViewEvent`. More moving parts.
   - **Pick A.** That means `OpenStandupModal` carries `routineUid` not `questions`, and the question lookup happens in the resolver / `SlackApiEventConstructor.openStandupModalRequest(...)`.

**Infrastructure templates (`infrastructure/src/main/kotlin/dev/notypie/templates/`)**

7. `StandupModalIds.kt` (new) — `CALLBACK_ID = "standup_answer_modal"`, `BLOCK_ID_PREFIX = "standup_q_"`, `ACTION_ID = "standup_answer"` (or per-question action_ids). Mirror `DeclineReasonModalIds`.

8. `ModalTemplateBuilder.kt` — add `standupModalViewJson(routineName, sessionDate, sessionUid, userId, questions: List<String>): String`. Each question becomes an `input` block with a multi-line `plain_text_input` element. `private_metadata` = `"$sessionUid,STANDUP_ANSWER_SUBMIT,$userId"` (token-comma format the parser already understands).

9. `SlackTemplateBuilder.kt` — add `standupModalViewJson(...)` to the interface.

**Infrastructure command wiring**

10. `impl/command/SlackApiEventConstructor.kt` — add `openStandupModalRequest(commandBasicInfo, commandDetailType, triggerId, sessionUid, routineUid, routineName, sessionDate, questions, userId, noticeChannel, noticeMessageTs): OpenViewEvent`. Inside, call `templateBuilder.standupModalViewJson(...)`. Mirror `openDeclineReasonModalRequest`.

11. `impl/command/SlackIntentResolver.kt` — replace `is CommandIntent.StandupFill -> null` with the real implementation: load the routine via injected `StandupRepository`, extract `questions`, then construct `OpenViewEvent` via the new `openStandupModalRequest`. Add `is CommandIntent.OpenStandupModal -> ...` and `is CommandIntent.RecordStandupAnswer -> RecordStandupAnswerEvent(...)` branches. **Note:** the resolver currently has no repository dependency — adding `StandupRepository` is OK (it lives in the same module). Update the `SlackRequestBuilderConfiguration.slackIntentResolver(...)` bean factory to inject it.

12. `impl/command/SlackInteractionRequestParser.kt` — already handles `view_submission` for the decline-reason modal. Generalise the per-block selection extractor so it handles `plain_text_input` blocks per question for the standup modal, OR have `StandupAnswerSubmissionContext` read `view.state.values` directly via the existing parser surface. Look at how `DeclineReasonSubmissionContext` reads `currentAction.selectedValue` — extend that pattern to multiple inputs.

**Repository extension**

13. `repository/standup/JpaSessionDispatchRepository.kt` is unchanged. **The new write goes against `standup_answer`.** Add to `JpaStandupSessionRepository.kt` (or new `JpaStandupAnswerRepository.kt`):
    ```kotlin
    @Query("""
        SELECT s FROM standup_session s WHERE s.sessionUid = :sessionUid
    """)
    fun findBySessionUid(sessionUid: UUID): StandupSessionSchema?
    ```
    Then in `StandupRepositoryImpl`, add `recordAnswer(sessionUid: UUID, userId: String, responses: List<String>, submittedAt: Instant): Boolean`:
    1. Find session by sessionUid (eager fetch).
    2. Replace existing answer row by `(session_id, user_id)` if exists, else insert. Easiest: `session.answers.removeIf { it.userId == userId }; session.answers.add(StandupAnswerSchema(...)); jpaStandupSessionRepository.save(session)`.

**Application service**

14. `application/service/standup/StandupAnswerService.kt` (new) — `@Service`. `@EventListener fun recordAnswer(event: RecordStandupAnswerEvent)` calls `standupRepository.recordAnswer(...)` inside a `@Transactional` block. Wire as `@Bean` if needed (auto-detected via `@Service`).

### Tests (target ~6 cases)

- `StandupFillContextTest` — button click → emits `OpenStandupModal` with the right fields.
- `StandupAnswerSubmissionContextTest` — view_submission → emits `RecordStandupAnswer` + `UpdateNoticeMessage`.
- `SlackIntentResolverTest` — `OpenStandupModal` → `OpenViewEvent` (synchronous dispatch). `RecordStandupAnswer` → `RecordStandupAnswerEvent`.
- `StandupAnswerServiceTest` — listener calls `standupRepository.recordAnswer(...)`.
- `ModalTemplateBuilderTest` — `standupModalViewJson` produces a Slack-valid view JSON with input blocks per question (round-trip via Slack SDK `View` deserializer like the decline-reason test).

### Effort estimate
**1–1.5 days** of focused work. ~14 files touched, ~500–700 LoC added net (mostly modal JSON + tests).

### Cross-cutting reminders
- **trigger_id expires in 3s** — `OpenStandupModal` MUST go through the synchronous `OpenViewEvent` path (`isInternal = true`, dispatched on the request thread). Look at how `OpenDeclineReasonModal` is routed.
- **Submission idempotency** — Slack delivers `view_submission` once unless the user re-clicks Submit. The repository upsert pattern (replace by `(session_id, user_id)`) handles dedup correctly.
- **Test fixture** — when adding `StandupRepository` to `SlackIntentResolver`'s constructor, every test that constructs the resolver needs the new arg. Use a relaxed `mockk<StandupRepository>()` default in the existing test fixture.

---

## Pickup Point — Phase 3 #13 (channel summary post) · ✅ SHIPPED in `b966f96`

> **HISTORICAL — this work is done.** As-built: `StandupSchedulingService.detectCutoffs()` publishes `StandupCutoffEvent`; `StandupSummaryService.postSummary(@EventListener)` loads the session + answers, builds the summary via `slackEventBuilder.standupSummaryRequest()` (template `ModalTemplateBuilder.standupSummaryTemplate`), saves the outbox row, then calls `standupRepository.markSessionSummarized(...)` (CAS) — replacing the temporary `outbox:<eventId>` marker with the real Slack `ts`. Kept below for reference only.

After #11 ships, #13 is small.

### What ships in #13

At cutoff time, the bot posts a single markdown summary to `routine.summaryChannel`. Members who responded show their answers; non-responders show `(no response)`. Session transitions `COLLECTING → SUMMARIZED` atomically (CAS) and `summaryMessageTs` gets the Slack `chat.postMessage` ts so a restart-after-cutoff doesn't double-post.

### Format example

```
📋 Daily Standup — 2026-05-04

@junho
  ▸ What did you do yesterday? Phase 3 #12 outbox claim/lock
  ▸ What are you doing today? Phase 3 #13 summary
  ▸ Any blockers? None

@minji (no response)

@hyun
  ▸ ...
```

### File-by-file work plan (~6 files)

1. `domain/command/entity/event/Event.kt` — add `StandupCutoffEvent(sessionUid: UUID)` (internal). The current `StandupSchedulingService.detectCutoffs()` only logs; refactor it to publish this event.

2. `application/service/standup/StandupSchedulingService.kt` — `detectCutoffs()` publishes `StandupCutoffEvent` for each expired COLLECTING session.

3. `application/service/standup/StandupSummaryService.kt` (new) — `@Service` with `@EventListener fun postSummary(event: StandupCutoffEvent)`:
   1. Load the session (with answers) + routine.
   2. Build a `SendSlackMessageEvent` (chat.postMessage to `routine.summaryChannel`) with the formatted summary.
   3. Save outbox row directly (same pattern as `StandupSchedulingService.processDispatch`). Use a `TransactionTemplate` if running outside a request transaction.
   4. After publish, call `standupRepository.markSessionSummarized(sessionId, messageTs)`. The CAS ensures a restart-after-cutoff is a no-op.

4. `infrastructure/templates/ModalTemplateBuilder.kt` — add `standupSummaryTemplate(routineName: String, sessionDate: LocalDate, members: List<RoutineMemberDto>, answers: List<StandupAnswerDto>, questions: List<String>): LayoutBlocks`. Render header + per-member sections, with `(no response)` for absent users.

5. `infrastructure/impl/command/SlackApiEventConstructor.kt` — add `standupSummaryRequest(...)` that calls `templateBuilder.standupSummaryTemplate(...)` and builds the channel `chat.postMessage` event.

6. **Tests**:
   - `StandupSummaryServiceTest` — happy path (post + mark summarized), idempotent on rerun (`markSessionSummarized` returns false → log warn, no double post).
   - `ModalTemplateBuilderTest` — summary template rendering with all members responded, partial responses, all absent.

### Cross-cutting reminders for #13
- **Idempotency** — `markSessionSummarized` is the atomic CAS gate. Re-running on a SUMMARIZED row is a no-op. Critical for restart safety.
- **summaryMessageTs persistence** — the Slack `chat.postMessage` API returns the ts in its response. The outbox dispatcher will need to thread that response back so `markSessionSummarized` can store it. Look at how the meeting-approval flow stores the original `message_ts` for `chat.update` on decline.
- **#13 closes the loop** — once it ships, the standup bot is functionally complete. `/standup setup` slash command (creating new routines from chat) is **NOT** in #13's scope; routines today are inserted via SQL or a future #17.

### Effort estimate
**0.5–1 day**. ~6 files, ~300–400 LoC.

---

## Suggested Starting Point

**Phase 3 is complete and committed.** The working tree is clean. Next work is Phase 4:

1. **#14 Meeting reminders (recommended start)** — reuse the Phase 3 standup scheduler (`StandupSchedulingService`) to fire 5-min / 15-min pre-meeting reminders. A few hours of work.
2. **#15 Prometheus metrics** — Slack API failure rate, retry exhaustion, outbox throughput, in-flight gauge.
3. **#16 History domain persistence** — wire up the dormant `HistoryRepository` (skeleton present, no business logic).

Separately, see `Refactor.md` for a standing list of code-quality cleanups to apply across the codebase (transaction placement on repository impls, TestFixtures dedup, file consolidation, higher-order/scope functions, DSL usage).
