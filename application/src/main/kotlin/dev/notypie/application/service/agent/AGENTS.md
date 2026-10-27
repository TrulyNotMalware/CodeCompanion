<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-27 -->

# application/service/agent

## Purpose
One AI-agent conversation turn, and the durable start of an `/ask` turn. `AgentConverseService` runs the turn for
the `AgentConverseRequestEvent` that `SlackIntentResolver` lifts from a free-text `@bot ...` mention, and for the
`/ask` turns that `AgentAskThreadStarter` hands it once the question is delivered: it calls the sidecar through
`AgentGateway`, then commits the resumable session id, an `agent_turn_history` audit row, and the
staged Slack reply in one transaction. Turns run on `agentTurnExecutor` (declared in
`configurations/AgentConfiguration`, sized by `slack.app.agent.turns.*`: 4 concurrent turns, a queue of 20, 20 s
shutdown wait), which is submitted to directly rather than through `@Async`, so a full
executor is seen: the requester gets `OVERLOADED_MESSAGE` (written in its own transaction) and
`agent.turns{outcome=rejected}` counts it. Each submission is an `AgentTurn(start, onDiscard)`: a turn still
queued when the executor's shutdown wait ends is discarded by `AgentTurnExecutor`, and its requester gets the same
notice (`agent.turns{outcome=discarded}`, WARN log). The notices stop at `AGENT_TURN_DISCARD_BUDGET` (3 s from the
end of the wait); the turns left then are dropped without a notice (one ERROR log, `agent.turns{outcome=dropped}`
by their count). A mention turn exists in memory only. An `/ask` turn is recorded in the `agent_ask_turn` ledger
before its question row is marked `SUCCESS` and runs under an `AgentTurnLease` on that row, so a crash, a restart,
a busy sidecar or a failed outcome transaction leaves a row that the starter's 60 s sweep runs again until the turn
has an outcome: an answer, the failure notice, the overload notice or the give-up notice (2026-10-13).
`AgentUsageReportService` reads the audit back: `@bot usage [days]` and the MCP tool `get_ai_usage`
summarise the turns and MCP tool calls of the last N days. `AgentRetentionScheduler` purges, hourly, the ledger's
finished rows and both audit tables past `slack.app.agent.retention.*` (2026-10-15).

## Key Files
| File | Description |
|------|-------------|
| `AgentConverseService.kt` | `class AgentConverseService(agentGateway, agentSessionRepository, agentTurnHistoryRepository, outboundStager, eventPublisher, meterRegistry, transactionManager, turnExecutor, clock, scopedTurnTokenCodec: ScopedTurnTokenCodec? = null)`. The file also declares `enum class BusyNotice { CONVERSATION_BUSY, ANSWER_LATER, NONE }` (the notice a busy run owes) and `interface AgentTurnLease { begin(): Boolean; settle(); giveBack(): BusyNotice; abandon(); discarded(): Boolean }` with `AgentTurnLease.Unleased` (`begin` true, `giveBack` `CONVERSATION_BUSY`, `discarded` false, the rest no-ops), and `class AgentTurnLeaseLostException(message) : IllegalStateException` — what a lease write throws when its CAS did not update one row; the outcome transactions log only that exception at WARN as superseded (lease table below). `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true) handleAgentConverse(event)` (so a mention whose transaction rolls back, and is then retried by Slack, never starts a turn, while an event published with no transaction active still runs) is `submit(event, AgentTurnLease.Unleased)` with the result dropped: a listener's return value would be published as an event. `submit(event, lease): Boolean` hands `converse` to the bounded `turnExecutor`, so the turn leaves the calling thread, and returns whether the executor took it; a refusal gets the reject handling of the lease table below. `offer(event, lease): Boolean` is the same hand-off with no effect at all on a refusal (the entry of the sweep and of a delivery that found its ledger row). `abandon(event, lease): Boolean` stages `FAILURE_MESSAGE` as a thread reply and calls `lease.abandon()` in one transaction, with no history row, and returns false when that transaction failed. A turn builds `sessionKey = "$channel:$threadId:$publisherId"` (`"$channel:$publisherId"` without a thread or with a blank one), then, inside `containFailure` (infrastructure `common`), calls `lease.begin()` (false → INFO log, nothing else) and builds `AgentTurnRequest(sessionKey, prompt, sessionId = findProviderSessionId, userId, appendSystemPrompt = contextPrompt, scopedToken = codec?.mint(...))`. An `Exception` there (2026-10-26; before, a failed session read escaped to the pool thread with no reply, no metric and no keyed log) is one ERROR `Agent turn failed before the sidecar call … sessionKey=… idempotencyKey=…` with its cause and `agent.turns{outcome=failed}` (no duration, no history row); only an `Unleased` turn also stages a best-effort `FAILURE_MESSAGE` thread reply, while a leased turn writes nothing (a `publishFailure` there would race `settle`) and is left to the ask sweep; an interrupt is restored and rethrown and an `Error` propagates, both to the pool thread's `AGENT_TURN_UNCAUGHT_LOGGER` (`configurations/AgentConfiguration.kt`). It then sends the request and branches on `AgentTurnResult`: `Completed` → `publishAnswer` (save provider session id, record `COMPLETED` with token counts; the answer is cut by `capAnswer` at `MAX_ANSWER_LENGTH` (`CHAIN_TEXT_BUDGET`, 40,000: every part rides in one chain head whose CDC update record must stay within 1 MiB, ending in `TRUNCATION_MARKER`, never splitting a surrogate pair: `domain/common/takeSurrogateSafe`), blank text → `EMPTY_RESPONSE_MESSAGE`, then `splitMessageText` cuts it into at most `MAX_ANSWER_MESSAGES` (8) bodies of `SlackBlockLimits.MESSAGE_BODY_BUDGET` with code fences closed and re-opened across the cut; each body is a thread reply headlined `RESPONSE_HEADLINE`, or `RESPONSE_HEADLINE (i/n)` when there are several, and all of them go out as one `outboundStager.stageInOrder` event (one outbox row; the relay stages each next part after the previous one is recorded)); `Busy` → `lease.giveBack()` first, then an exhaustive `when` over the `BusyNotice` it returns, in that transaction: `CONVERSATION_BUSY` (no lease) → ephemeral `BUSY_MESSAGE` + `BUSY` row, `ANSWER_LATER` (a leased turn's first run) → ephemeral `ANSWER_LATER_MESSAGE` + `BUSY` row, `NONE` (a later leased run) → nothing else is written and an INFO line follows the commit; `Failed` → `FAILURE_MESSAGE` + `FAILED` row with `errorCode`. `Completed` and `Failed` end with `lease.settle()` in the same transaction. Records `agent.turns`, `agent.turn.duration`, `agent.tokens` The capped answer goes through `templates/neutralizeBroadcastMentions()` before the split: `<!channel>` / `<!here>` / `<!everyone>` (also echoed from user text or a tool result) stay literal while links, emphasis and `<@user>` mentions keep working. |
| `AskSlashService.kt` | Interface `handleAsk(headers, payload: SlashCommandRequestBody, commandData)` plus `AskSlashServiceImpl` (`@Service`, `@Transactional`): resolves the actor role with `CommandRoleResolver.resolve`, builds `AskCommand(idempotencyKey, commandData, actorRole)` and runs it through `CommandExecutor`; the dependency `SlashCommandController` and `SocketModeReceiver` take for `/ask` |
| `AgentAskThreadStarter.kt` | `@Service class AgentAskThreadStarter(outboxRepository, askTurnRepository, agentConverseService, retryService, meterRegistry, clock, appConfig)`; `internal` constants `ASK_RESUME_PERIOD_MILLIS` (60 000), `ASK_RESUME_BATCH_SIZE` (20), `ASK_UNREADABLE_LOOK_AHEAD_MAX` (80, 2026-10-15), `ASK_MAX_RUNS` (5), `ASK_PENDING_GRACE` (60 s), `ASK_ORPHAN_MARGIN` (60 s), `ASK_GIVE_UP_AFTER` (1 h), no YAML key. `@EventListener onDelivered(MessageDeliveredEvent)`, on the relay's dispatch thread before the outbox status write: another detail type than `AGENT_ASK_QUESTION` → return; blank `messageTs` → WARN, return; the outbox row is read and decoded (`OutboundMessageCodec`) inside `retryService.execute(maxAttempts = STATUS_WRITE_ATTEMPTS)` — row gone or not a `ChannelMessage` with `MessageContent.AgentQuestion` → WARN; `schemaVersion` outside `OutboxSchemaVersion.SUPPORTED` or undecodable → ERROR (2026-10-14; the relay gates by version before delivering, so this should not happen); either way return, no ledger row and no retry, a read that throws on every attempt propagates to the relay; then `askTurnRepository.recordDelivered(eventId, messageTs, now)` in a second retry of the same size; if that still fails → ERROR (eventId, idempotencyKey, cause) and `agentConverseService.submit(event, AgentTurnLease.Unleased)`; otherwise, when the row is `PENDING`, the hand-off follows `RecordedAskTurn.storedNow`: a row this call stored → `submit(event, lease)`, a row that already existed → `offer(event, lease)` (a refusal then writes nothing), both with `AgentConversePayload.threadId` = the row's `message_ts`, so the answer arrives as thread replies (a row in any other status starts nothing). `@Scheduled(fixedDelay = ASK_RESUME_PERIOD_MILLIS, initialDelay = ASK_RESUME_PERIOD_MILLIS) resume()` → `containFailure { resumeOnce() }` (an `Exception` is logged, an `Error` propagates). `resumeOnce(): Int` reads `findResumable(pendingBefore = now − ASK_PENDING_GRACE, orphanedBefore = now − (agent.sidecar.requestTimeoutSeconds + ASK_ORPHAN_MARGIN), limit = ASK_RESUME_BATCH_SIZE + min(unreadableLastSweep, ASK_UNREADABLE_LOOK_AHEAD_MAX))` and handles each row inside its own `containFailure` (ERROR naming the eventId, then the next row); each row ends as a private `ResumeStep` (`OFFERED`, `UNREADABLE`, `NOT_OFFERED`), and after the batch `@Volatile unreadableLastSweep` is set to the number of `UNREADABLE` rows, so the next sweep reads that many rows more and reaches the rows behind them (2026-10-15, decisions #40 ⑤; the count is this JVM's own, never written to a row, and back to 0 once those rows are abandoned or readable; while unreadable rows persist — up to `ASK_GIVE_UP_AFTER` — each sweep logs one ERROR per unreadable row it reads, so up to 100 ERROR lines a minute per replica): an `IN_PROGRESS` row is `releaseOrphan`ed first (lost CAS → skipped); a row with `runCount >= ASK_MAX_RUNS` or `createdAt` older than `ASK_GIVE_UP_AFTER` goes to `agentConverseService.abandon` (committed → ERROR log, `agent.turns{outcome=abandoned}`); any other row is rebuilt from its outbox row and handed over with `agentConverseService.offer` (accepted → counted in the return value and in `agent.turns{outcome=resumed}`). The question row is read through `questionRowOf` (private sealed `QuestionRow`, bound as `row` at the three `when` sites, 2026-10-14): `Absent` (no row, or content other than a `ChannelMessage` carrying an `AgentQuestion`) is `finish(ABANDONED)`ed with no notice, the WARN and `outcome=abandoned` following only when that CAS won; `Unreadable` (`schemaVersion` not in `OutboxSchemaVersion.SUPPORTED` — checked before decoding, because the codec ignores unknown properties and would silently drop a newer field — or an `OutboundMessageCodecException`, whose reason is built by `reasonOf` from the cause's class and `JacksonException.getPathReference()` — class and field names only, one line; it carries no payload text as long as no field of the payload model is a `Map`, which is true of `domain/command/outbound` today) is left with one ERROR per sweep, no count and no write beyond the orphan release an `IN_PROGRESS` row gets first, until the give-up condition, when `abandonWithoutNotice` runs the `finish(ABANDONED)` CAS (ERROR `… abandoned without a notice after N runs, delivered at …: its question cannot be read (<reason>)` and `outcome=abandoned` only when that CAS won; no thread notice can be rebuilt because the channel lives only in the unreadable row — a DM to the outbox row's `publisherId` would be a separate feature). On delivery an `Unreadable` row is an ERROR and no turn starts; `Absent` keeps the WARN. `private inner class AskTurnLease(eventId, attempt)` is the lease over the row: `begin` = the `begin` CAS with the observed `attempt_count` (then `attempt + 1`), `settle` / `abandon` = `finish(COMPLETED)` / `finish(ABANDONED)`, `giveBack` = `release`, returning `BusyNotice.ANSWER_LATER` on the lease's first run and `NONE` on a later one (`attempt > 1`), `discarded` = true; `settle`, `giveBack` and `abandon` throw `AgentTurnLeaseLostException` when their CAS did not update one row |
| `AgentUsageReportService.kt` | `class AgentUsageReportService(agentTurnHistoryRepository, mcpToolCallHistoryRepository, outboundStager, eventPublisher, clock, transactionManager)`. `@EventListener handleUsageReport(AgentUsageReportRequestEvent)` stages one `Ephemeral` to the requester (`recipient = UserRef(responseBasicInfo.publisherId)`) headlined `CodeCompanion — AI usage`, typed `AGENT_USAGE_REPORT`, and publishes it, as `OpsStatusService.handleStatusReport` does. The listener renders inside `detachedTemplate` (`application/common`, `PROPAGATION_NOT_SUPPORTED`), so a failing `@Transactional` repository read rolls back only its own transaction instead of marking the mention's transaction rollback-only and losing the reply; an `Exception` is logged and answered "Failed to read AI usage. Check application logs.", an `Error` propagates. `internal renderReport(days)` (shared with `mcp/DomainReadTools.get_ai_usage`): `since = LocalDateTime.now(clock).minusDays(days)`, then `countByOutcomeSince`, `topRequestersSince(limit = 5)` and `countByToolSince`. No turns and no tool calls → "No AI turns or tool calls in the last N day(s)."; otherwise mrkdwn lines: header with `since` as `yyyy-MM-dd HH:mm`, `• Turns: T (completed X, busy Y, failed Z)` (outcomes present, in enum order; `• Turns: 0` when none), `• Tokens: A in / B out`, `• Avg duration: S.s s` (only with turns), `• Top requesters: <@U> N turn(s), A in / B out; …` and `• MCP tools: tool N outcome, …; …` (each only when non-empty). Counts use `%,d` with `Locale.ENGLISH` |
| `AgentRetentionScheduler.kt` | `@Component class AgentRetentionScheduler(askTurnRepository, agentTurnHistoryRepository, mcpToolCallHistoryRepository, appConfig, clock)` (2026-10-15); `internal` constants `AGENT_RETENTION_PERIOD_MILLIS` (1 h), `AGENT_RETENTION_FIRST_RUN_MIN_MILLIS` / `AGENT_RETENTION_FIRST_RUN_MAX_MILLIS` (10 / 20 min); the batch cap is the shared `relay/RETENTION_MAX_BATCHES_PER_TICK` (20) since 2026-10-20. `@Scheduled(fixedDelay = 1 h, initialDelayString = "${random.long[600000,1200000]}") purge()` (2026-10-19): each instance draws its first run between 10 and 20 minutes after start from Boot's `random.*` property source, after the outbox purge's 5 min, so the two replicas that `Recreate` starts together do not purge the same range at the same moment every hour (the placeholder needs a Boot environment; a bare `ApplicationContext` without `RandomValuePropertySource` fails to resolve it) → `purgeOnce(): Int`, which takes one `now` from the clock and purges three tables in turn, each against its own cutoff: `agent_ask_turn` `COMPLETED`/`ABANDONED` rows with `updated_at` before `now − agent.retention.ask-turn-days` (default 14) through `deleteFinishedOlderThan`, then `agent_turn_history` and `mcp_tool_call_history` rows with `created_at` before `now − agent.retention.history-days` (default 180) through the two ports' `deleteOlderThan`. Each table runs (`purgeTable`) batches of `agent.retention.batch-size` (default 1000, at most 10,000), each its own transaction, until one comes back short or after 20 batches (the rest waits an hour) — the loop is `relay/purgeInBatches`, shared with the outbox purge; the batch lambda adds each batch to the table's own total, so the failure line still names the rows already deleted — inside its own `containFailure`: an `Exception` is one ERROR naming the table and the rows already deleted, and the next table still runs; an `Error` propagates; an `InterruptedException` re-sets the flag and propagates. One INFO line per table that lost rows, nothing for a table that lost none. `PENDING`/`IN_PROGRESS` ledger rows are never deleted (deleting one would make its owner's next CAS lose and roll its answer back). Every replica runs it; when two replicas overlap it is the same exposure as the outbox purge (lock waits, a rare deadlock retried next hour). Keep `purgeOnce` and `purgeTable` without `@Transactional` and without a read before the delete: each `DELETE … LIMIT` must be its transaction's only statement (`infrastructure/repository/agent/AGENTS.md`: held locks and MariaDB snapshot-isolation 1020 otherwise). On MariaDB 12.3.3 (2026-10-20) every purge statement is an index range, two sessions purging one range add up exactly, and the real tick deletes at most `batch-size` rows per transaction (`infrastructure/repository/agent/AGENTS.md`). `agent_session` is not purged (deleting it would end the thread's sidecar session continuity; the sidecar's own session expiry is unverified) |

## For AI Agents

### Working In This Directory
- `converse` runs on `agentTurnExecutor` (`slack.app.agent.turns.max-concurrent` threads, bounded queue) with no
  ambient transaction, so every outcome's writes go through `transactionTemplate.runInTx { ... }`. The overload
  notice is the exception: it is written on the committing thread inside `afterCompletion`, where the committed
  transaction is still bound, so it uses a `PROPAGATION_REQUIRES_NEW` template (a REQUIRED one joined the finished
  transaction and the notice never committed; pinned by a real `JpaTransactionManager` case in the spec). The
  committed transaction still holds its connection there, so each rejected mention takes two pool connections at
  once; the Hikari sizing rule in `src/main/resources/AGENTS.md` counts them. That
  boundary is what lets `SlackMessageRelayServiceImpl.saveOutboxMessage` (`BEFORE_COMMIT`) persist the
  staged reply; publishing outside it silently loses the message. `runInTx` swallows into a
  `Result` — failures are logged with `sessionKey` and `idempotencyKey`, never rethrown.
- **An `/ask` turn starts from the ledger; the order of the two writes is the point.** `onDelivered` runs
  synchronously inside `SlackMessageRelayServiceImpl.dispatchClaimed`, after Slack accepted the question and before
  the outbox row is recorded `SUCCESS` (`service/relay/AGENTS.md`). A crash before the ledger insert leaves the
  outbox row `IN_PROGRESS`, so the outbox sweep posts the question again and that delivery records the turn; a crash
  after it leaves a ledger row the ask sweep resumes on the first post's thread. The ledger write is not inside the
  status transaction on purpose: a ledger failure there would roll `SUCCESS` back and re-post the question, while
  here it costs only that turn's durability (the turn still runs, unleased). This holds only while the listener
  runs on the publishing thread — `ApplicationContextSmokeTest` asserts the row exists when `publishEvent` returns;
  an `@Async` listener, an async multicaster or a move back behind `MessagePublishSuccessEvent` breaks it silently.
  `recordDelivered` refuses to run inside a transaction (`infrastructure/repository/agent/AGENTS.md`); the relay
  dispatch thread has none.
- **Lease table** (what each moment writes; `AgentConverseServiceTest` has a case per cell that is not "—"):

  | moment | no lease (mention; `/ask` whose ledger write failed) | leased (`/ask`) |
  |---|---|---|
  | executor full: a mention, or the delivery that stored the row (`submit`) | `OVERLOADED_MESSAGE`, `outcome=rejected` | the same notice and `lease.settle()` in one `REQUIRES_NEW` transaction: the row is `COMPLETED` without a run |
  | executor full: the sweep, or a delivery that found its row (`offer`) | — | nothing is written: the row stays `PENDING` with its counters and `updated_at`, and the next sweep offers it again |
  | executor already shut down (`submit`) | the discard path: WARN, `outcome=discarded`, `OVERLOADED_MESSAGE` | `lease.discarded()` → no notice, the row stays `PENDING` |
  | discarded unstarted when the shutdown wait ends | the same discard path | the same: no notice, the row stays `PENDING` for the next start |
  | turn starts | — | `lease.begin()`; false → no sidecar call, no metrics |
  | an `Exception` before the sidecar call (`begin`, the session read, the context prompt, the token mint) | ERROR with `sessionKey` and `idempotencyKey`, `outcome=failed`, best-effort `FAILURE_MESSAGE` thread reply, no history row | the same ERROR and count, nothing written: the ask sweep takes the row up again (an `IN_PROGRESS` row once the orphan cutoff passes, a `PENDING` one when `begin` failed before its write) |
  | `Completed` | answer transaction | the same transaction with `lease.settle()` as its last statement |
  | `Busy`, the turn's first run | `BUSY` row + ephemeral `BUSY_MESSAGE` | `lease.giveBack()`, then the `BUSY` row + ephemeral `ANSWER_LATER_MESSAGE`, one transaction |
  | `Busy`, a later run | — (a mention has no later run) | `lease.giveBack()` only |
  | `Failed` | `FAILED` row + failure notice | the same transaction + `lease.settle()` |
  | give-up (`abandon`) | — | `FAILURE_MESSAGE` thread reply + `lease.abandon()`, one transaction, no history row |

  A lease write that loses its CAS throws `AgentTurnLeaseLostException`, so its transaction rolls back and a run that lost the
  turn stages nothing. Every outcome transaction (`publishAnswer`, `publishBusy`, `publishFailure`, `publishOverloaded`, `abandon`)
  ends in `Result<*>.reported(event, what, failure): Written` (`POSTED` / `SUPERSEDED` / `FAILED`): that exception is logged at WARN
  (`Agent turn superseded; <what> not posted idempotencyKey=… (<message>)`, no stack trace, `<what>` ∈ `answer`, `busy give-back`,
  `failure notice`, `overload notice`, `abandon notice`) and `recordMetrics` tags the run `superseded` instead of `completed` / `busy` /
  `failed` (the duration timer alike; `agent.tokens` is still counted — the sidecar did bill them). Any other failure keeps its ERROR
  and its old counting; an executor rejection whose settle is lost stays `rejected` (that path never reaches `recordMetrics`). Since
  2026-10-14; before, a lost lease was an ERROR with a stack trace and counted as the outcome it rolled back. The executor counts as shut down only when it is a `ThreadPoolTaskExecutor` whose pool `isShutdown`
  (`AgentTurnIntake.stop()` does that at the start of the close); any other `Executor` is never "shut down".
- **Only the delivery that stored the row ends a turn because an executor was full.** That rejection is the
  lane's admission control: a full executor sheds a new question at once, with the overload notice, instead of
  queueing it durably for up to an hour. It is safe there because a row this call just stored cannot be in another
  replica's queue through a sweep: a sweep reads only `PENDING` rows older than `ASK_PENDING_GRACE` (replica clocks
  are assumed to agree to well within that). Every other hand-off uses `offer` and writes nothing when refused: the
  sweep, and a delivery that found its row — the outbox posted the question again after a lost status write, or
  the insert of this very listener call committed and only its acknowledgement was lost. Prod runs two replicas,
  and such a rejection must not close a turn another replica has queued or one that was given back as busy
  (2026-10-13; before that a re-delivery on a full replica settled the turn another replica's sweep had queued).
  Only `run_count >= ASK_MAX_RUNS` or the `ASK_GIVE_UP_AFTER` window ends a turn from the sweep, through `abandon`.
- **Two counters.** `attempt_count` is the lease's ownership token and nothing else. The give-up budget is
  `run_count`: `begin` raises both, the busy give-back takes the run back, an orphan release and `finish` touch
  neither (`infrastructure/repository/agent/AGENTS.md`). So a sidecar that keeps answering busy or 429 never
  spends the budget; that turn retries every sweep after `ASK_PENDING_GRACE` until the window ends it. Only its
  first run tells the requester; later give-backs are silent, and `agent.turns{outcome=busy}` still counts each.
  That one notice is `ANSWER_LATER_MESSAGE`, not `BUSY_MESSAGE`: a fresh `/ask` thread has no "previous request in
  this conversation", and the ephemeral is its own outbox row, so Slack may deliver it after the resumed run's
  answer; the sentence no longer claims that work is in progress, so it does not contradict an answer that is
  already there. It is not a guarantee either: the turn can still end with `FAILURE_MESSAGE` instead of an answer.
- **The orphan cutoff must exceed the longest turn.** It is `agent.sidecar.requestTimeoutSeconds` +
  `ASK_ORPHAN_MARGIN`, never a literal: `SidecarAgentClient` bounds a whole turn by that timeout
  (`infrastructure/impl/agent/AGENTS.md`), and the margin is for the outcome transaction. A released run that
  finishes before anyone began the turn again still settles (`finish` accepts `PENDING`); once another run
  began, its settle is refused and its answer rolls back.
- **`queued` is this JVM's view only.** The set of event ids keeps the sweep from offering a turn that already
  waits in this instance's executor queue: an id is added before the hand-off and removed when the hand-off was
  refused, when the turn begins (after its CAS) and when it is discarded. Another replica cannot see it, so two
  replicas may both queue one turn; the `begin` CAS decides and the loser does nothing.
- **A question in a channel Slack refuses starts no turn** (B-2, 2026-10-14). The question row carries
  `fallback = askRefusedNotice(...)` (says the question was not answered and quotes it); on `not_in_channel` / `no_permission` the relay records `FAILURE`,
  publishes no `MessageDeliveredEvent` (so no ledger row and no turn) and stages the notice as a DM with no detail
  type, which `onDelivered` ignores. Residual: if the first post succeeded but its status write was lost and the
  bot left the channel before the re-post, the requester gets the notice for a question that is being answered.
- **Known limits of the `/ask` lane (2026-10-13).** An outbox read that fails on all three attempts, followed by
  a status write that succeeds, still loses the turn (question posted, no ledger row). The unleased fallback can
  answer twice in one thread when a ledger insert committed but its acknowledgement was lost and no later
  attempt of that listener call succeeded: the unleased turn answers, and the sweep runs the stored row after the
  grace period. (Two limits recorded here on 2026-10-13 — a lost lease logged at ERROR and counted as `completed`, and
  the sweep decoding without the `OutboxSchemaVersion.SUPPORTED` gate — were closed on 2026-10-14; see the lease table and the
  `AgentAskThreadStarter.kt` row.) Rows that cannot progress (queued here, rejected offers, a give-up whose notice fails, and
  an unreadable question row for up to `ASK_GIVE_UP_AFTER`) keep the oldest `updated_at` and so the head of the batch. Only
  the unreadable ones could hold it for long, and since 2026-10-15 the sweep reads past them: it widens its next read by the
  number of rows it found unreadable, up to `ASK_UNREADABLE_LOOK_AHEAD_MAX` (80) extra rows. A sweep reads at most 100 rows,
  so only a prefix of 100 unreadable rows stalls the rows behind them until the give-up (81 still leave 19 places). Unreadable
  rows come from a newer schema version (a rollback) or an undecodable payload. The look-ahead does not move the give-up
  deadline: a readable turn reached late can already be past `ASK_GIVE_UP_AFTER` and be abandoned. The widening lives in this
  JVM's memory and restarts at 0 with the process (the first sweep after a restart reads one batch again; the one after that
  looks past). The look-ahead itself writes nothing to an unreadable row (a waiting `PENDING` row is left as it is until the
  give-up; an orphaned `IN_PROGRESS` row is still released to `PENDING` with a new `updated_at` before its question is read,
  and the give-up writes `ABANDONED`, both as before), because whether a row can be read belongs to the binary, not the row. In a mixed-version fleet a replica that
  cannot read it would touch `updated_at` on every sweep, so the row would never be older than `ASK_PENDING_GRACE` when the
  replica that can read it sweeps; that replica would never pick it up, and the turn would end at the give-up without a
  notice (decisions #40 ⑤). A widened sweep can offer more than 20 rows when rows ahead of
  it were finished elsewhere meanwhile; the executor's bounded queue still refuses what does not fit.
  In a mixed-version fleet the first replica to sweep past the give-up window decides: if a replica that cannot read the row
  wins the `finish` CAS, the turn ends without a notice although a newer replica could have posted one — accepted, because the
  alternative leaves the row with no end once the whole fleet runs the older binary (2026-10-14). The version gate itself is a
  trade-off, not a pure protection: a question row stamped with a newer version used to be decoded with the unknown fields dropped
  and answered; from this binary on it is held for a replica that knows the version, and a rollback that outlasts
  `ASK_GIVE_UP_AFTER` abandons it without a notice (decisions #40 ④). The first-busy notice is skipped when the first run was cut off and a later run is busy; that
  includes a first run whose notice transaction rolled back, because after the orphan release the next run is the
  lease's second (never doubled: the notice commits only together with the first give-back). A first-busy notice
  that Slack delivers late can arrive after the answer; its wording no longer claims work is in progress. When
  two deliveries of one question race (two outbox owners both sent it), the instance that stored the row can shed
  it with the overload notice while the other has just queued it, and that queued turn then does nothing (by
  reading; no spec). A question whose dispatch outcome is unknown has no ts and gets no turn. Finished ledger rows are purged after
  `slack.app.agent.retention.ask-turn-days` (see the `AgentRetentionScheduler.kt` row). The
  `V26` script and the five native statements were run once on MariaDB 12.3.3 through the `mariadb` client
  (2026-10-13, see `infrastructure/repository/agent/AGENTS.md`); the application itself — the JPA mapping against
  the `V26` table and `recordDelivered`'s duplicate-key path — has only run on H2 (the specs use tables Hibernate
  generates).
- **Where `V26` is missing**, every `recordDelivered` fails: `/ask` still answers through the unleased fallback,
  without durability and with one ERROR per question (inferred from the code and the always-failing-insert case
  of `AgentAskThreadStarterTest`; not run against a schema without the table).
- `stageReply` is `checkNotNull` on `outboundStager.stage(...)`: a null stage means the reply would
  vanish, so the turn's transaction must fail instead. Keep that check when adding reply kinds.
- Session continuity is two keys: `sessionKey` (sidecar workspace, derived from channel, thread and requester)
  and the provider session id stored in `agent_session`, echoed back as `sessionId` on the next turn. It is
  stored by a single upsert (`now = LocalDateTime.now(clock)`) as the **first** statement of the answer
  transaction; a read before it in that transaction makes MariaDB's snapshot isolation (1020) roll the answer's
  outbox rows back (see `infrastructure/repository/agent/AGENTS.md`).
  Change the `sessionKey` formula and every open conversation loses its context. The requester is part of it
  because a provider session remembers the tool results fetched under that user's role: another participant in
  the same thread resuming it would read them without the per-call permission check. Sessions stored under the
  old `channel:thread` key are not resumed and start fresh.
- `contextPrompt` is appended to the sidecar's base prompt: requester, channel, current time in
  `clock.zone` (so relative dates resolve), and the Slack mrkdwn contract. Facts only — identity for
  authorization travels as the turn token / `X-User-Id` in `SidecarAgentClient`, never as prompt text.
- `requesterName` / `channelName` are user-controlled and go into the system prompt, so
  `sanitizeContextName` replaces control, format (bidi / zero-width) and line/paragraph-separator characters
  with spaces, replaces `"`, backticks and backslashes with `'`, collapses whitespace and caps the result at
  `MAX_CONTEXT_NAME_LENGTH` (64 UTF-16 units, cut one unit earlier when the cut would split a surrogate pair —
  `domain/common/takeSurrogateSafe`, shared with `capAnswer`).
  The name is then shown as quoted data — `<@id> (display name "…")`, `<#id> (channel name "…")` — under a
  line telling the model that quoted names are labels, not instructions. A value that is blank afterwards
  (app_mention events carry no names) degrades to the bare `<@id>` / `<#id>` mention. Route any new
  user-supplied prompt field through the same function and quoting.
- `scopedTurnTokenCodec` is null when MCP is off (`AgentConfiguration` uses `ObjectProvider`); the
  turn then carries no token and the model has no tools. `turnId` is the request's `idempotencyKey` (the
  mention's, or the `/ask` question's) so tool audit rows join back to `agent_turn_history`.
- A turn can last up to `slack.app.agent.sidecar.request-timeout-seconds` (120 s), longer than the 20 s shutdown
  wait: a rolling deploy cuts turns still running after that wait. A cut mention turn is lost; a cut `/ask` turn
  stays `IN_PROGRESS` and the sweep reruns it once the orphan cutoff has passed. Keep `turns.queue-capacity` small;
  a deep queue only converts an immediate "busy" reply into minutes of silence.
- A multi-part answer is one outbox row carrying every part (`stageInOrder`); the relay posts the parts in order,
  so the `(i/n)` headline only shows how many there are. Staging the parts as separate rows lets the parallel
  relay post them out of order.
- Metric names are `internal const` and dashboards depend on the `outcome` / `direction` tags. `agent.turns`
  outcomes: `completed`, `busy`, `failed`, `rejected`, `discarded`, `dropped`, and for the `/ask` lane `resumed`
  (a sweep offer the executor accepted), `abandoned` (a committed give-up, with or without a notice) and `superseded` (a leased
  run whose outcome transaction lost the lease CAS and rolled back; its tokens still count under `agent.tokens`). A turn
  refused because the executor is shut down counts as `discarded`, not `rejected`; a leased turn left `PENDING`
  at shutdown counts as neither.
- The usage window is computed from the injected `Clock` (`Clock.systemDefaultZone()`), and `created_at` on both
  history tables is `@CreationTimestamp` on the JVM clock in the same zone. Changing either zone shifts the window.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.service.agent.*' \
  --tests '*ApplicationContextSmokeTest*' --tests '*AgentTurnShutdownContextTest*'
```
`AgentConverseServiceTest` (Kotest `BehaviorSpec`, MockK) stubs `AgentGateway.converse` per outcome, a
MockK `PlatformTransactionManager` (relaxed `TransactionStatus`, `commit` / `rollback` `just Runs`), a
`SimpleMeterRegistry`, and a fixed `Clock`; it asserts the `AgentTurnRecord` fields, the staged
`OutboundMessage` shape (thread id, headline, ephemeral recipient for `Busy`), the saved provider
session id, and the counters, and it has one given per lease-table row with a MockK lease plus a real
`JpaTransactionManager` given for "a refused lease write rolls the outcome back". `AgentAskThreadStarterTest` runs
the starter over the real outbox and ledger repositories on H2 (`createOutboxJpaContext`) with a `MutableClock`,
against a recording MockK `AgentConverseService` and, for the rejected-offer, busy, two-instance re-delivery and the lost-settle supersede
cases, the real service and transaction manager. The two context specs pin what unit specs cannot see: the listener is synchronous, `resume`
is scheduled, `fallbackExecution` still works, and a turn refused or queued at shutdown stays `PENDING`. Any case
that claims the order of the two writes, a rollback or a CAS belongs in one of the H2 or context specs, not behind
a mock. `AgentUsageReportServiceTest` passes `createStubTransactionManager()` by default and
runs one `handleUsageReport` inside an outer H2 `DataSourceTransactionManager` transaction whose usage read fails
inside a participating template (`createH2TransactionManager` / `failInsideParticipatingTx`); the outer commit must
still succeed with the fallback text staged. Cover the null-codec and non-null-codec paths when touching token
minting; build the request event with the domain testFixtures rather than inline constructors.

### Common Patterns
- `sealed` result dispatch via `when (result)` with one private `publishXxx` per outcome.
- `turnRecord(...)` factory with defaulted nullables so every outcome writes the same row shape.
- `eventPublisher.publishOne(event = stageReply(...))` — replies are transport-neutral
  `OutboundMessage`s rendered at deliver time, never Slack blocks built here.
- `companion object` holding `internal const` copy so specs assert on the constants, not literals.

## Dependencies

### Internal
- `application/common/TransactionTemplateExt` — `runInTx`, `detachedTemplate`
- `application/security/mcp/ScopedTurnTokenCodec` — per-turn token
- `application/configurations/AgentConfiguration` — bean declaration, sidecar client, the turn executor;
  `AppConfig.agent.sidecar.requestTimeoutSeconds` (the orphan cutoff), `AppConfig.agent.retention` (the purge)
- `application/service/relay/` — publishes `MessageDeliveredEvent`; `STATUS_WRITE_ATTEMPTS` (shared on purpose:
  the ledger write gets as many attempts as the status write that follows it); `purgeInBatches` (the retention loop)
- `infrastructure/common/ExceptionBoundary.kt` — `containFailure`
- `infrastructure/impl/agent/` — `AgentGateway`, `AgentTurnRequest`, `AgentTurnResult`, `SidecarAgentClient`
- `infrastructure/repository/agent/` — `AgentSessionRepository`, `AgentTurnHistoryRepository`,
  `AgentTurnRecord`, `AgentTurnOutcome`, `AgentTurnOutcomeUsage`, `RequesterTurnUsage`; the ledger
  `AgentAskTurnRepository` (+ `recordDelivered`), `schema/AgentAskTurnSchema`, `AgentAskTurnStatus`
- `infrastructure/repository/outbox/` — `MessageOutboxRepository.findById`, `OutboundMessageCodec`,
  `dto/MessageDeliveredEvent`; `infrastructure/impl/retry/RetryService`
- `infrastructure/repository/mcp/` — `McpToolCallHistoryRepository`, `ToolCallUsage`; `deleteOlderThan` on both history ports and
  `AgentAskTurnRepository.deleteFinishedOlderThan` (the purge)
- `infrastructure/impl/command/SlackIntentResolver` — publishes `AgentConverseRequestEvent` and `AgentUsageReportRequestEvent`
- `domain/command/entity/event/` — `AgentConverseRequestEvent`, `AgentConversePayload`, `EventPublisher.publishOne`
- `domain/command/outbound/` — `OutboundMessage`, `MessageContent`, `ConversationTarget`, `UserRef`, `OutboundMessageStager`
- `domain/common/takeSurrogateSafe` — `capAnswer` and `sanitizeContextName`

### External
Spring `@TransactionalEventListener`, `@EventListener`, `@Scheduled`, `ThreadPoolTaskExecutor`, Spring TX
`TransactionTemplate`, Micrometer `MeterRegistry`, kotlin-logging.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
