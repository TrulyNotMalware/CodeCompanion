<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-10-20 -->

# application/service/relay

## Purpose
The transactional-outbox relay. Two mutually exclusive readers — a fixed-rate DB poller and a Debezium
CDC Kafka listener — claim `PENDING` `outbox_message` rows and hand each claim to
`SlackMessageRelayServiceImpl.dispatchClaimed`, which renews the claim, renders through one
`OutboxPayloadRenderer`, dispatches through `MessageDispatcher`, and then either announces an accepted send
(`MessageDeliveredEvent`), writes `SUCCESS` / `FAILURE` guarded by the claim and publishes an `OutboxUpdateEvent`
(staging the next part of a chained reply in the same transaction), or leaves the row `IN_PROGRESS` for the recovery
sweep (rate-limited: deferred past `Retry-After`; transient: retried after the stuck threshold). `SlackMessageRelayServiceImpl` also owns the write side of the
interactive path: it turns `OutboundMessageEnqueued` into an outbox row at `BEFORE_COMMIT`.

## Key Files
| File | Description |
|------|-------------|
| `MessageProcessor.kt` | Marker `interface MessageProcessor` (no method since A4): each impl exposes its own typed entry point, so no impl narrows a shared parameter at runtime. Exists for `@ConditionalOnMissingBean(MessageProcessor::class)` bean selection |
| `MessageRelayService.kt` | `interface MessageRelayService { reserveDispatchSlots(wanted): Int; releaseDispatchSlots(count); batchPendingMessages(claims: List<OutboxClaim>); dispatchClaimed(claim: OutboxClaim) }` and `inline fun MessageRelayService.claimWithReservedSlots(wanted, claimRows: (slots) -> List<OutboxClaim>)`: reserves up to `wanted` slots, lets `claimRows` claim at most that many rows (`check`), gives the unused slots back in a `finally` (also when `claimRows` throws) and hands the claims to `batchPendingMessages`. A row is claimed only with a slot held for it, so the poller and the sweep, which run at the same time on the scheduler pool, never claim the same free room, and a claimed row never waits the stuck threshold for a full executor. `data class OutboxClaim(row: OutboxMessage, attempt: Int)` — the row plus the `attempt_count` this owner won, which is its ownership token — and the two claim helpers on `MessageOutboxRepository`: `claim(row, now)` (`claimPending` with the row's observed `attemptCount`) and `reclaim(row, olderThan, now)` (`reclaimStuck`, same guard); each returns an `OutboxClaim` with `attempt = row.attemptCount + 1` or `null` when the CAS was lost |
| `OutboxRecoveryScheduler.kt` | `@Component`, mode-independent, `@Scheduled(fixedDelay = RECOVERY_SWEEP_PERIOD_MILLIS, initialDelay = same)` (`const val RECOVERY_SWEEP_PERIOD_MILLIS = 60_000L`, also read by `OutboxHealthIndicator`) `recover()` → `recoverOnce()`. `now` comes from the injected `Clock`. `findStuckInProgress(updated_at < now - outbox.polling.stuckInProgressSeconds)` → rows with `sendCount >= outbox.polling.maxSends` (10) or `createdAt` older than `outbox.polling.giveUpAfterHours` (24) go to `abandonStuck` (FAILURE, guarded by the observed `attemptCount` and the same cutoff, so a renewed or reclaimed row is left alone; both abandon logs name the chained parts a V3 row drops), the rest to `reclaim`; plus `findStalePending` (PENDING older than the same threshold): rows whose `createdAt` is past `giveUpAfterHours` go to `abandonPending` (FAILURE without a claim, guarded by the observed `attemptCount`, logged at ERROR, also when the relay has no free slot), the rest to `claim`; both go through `claimWithReservedSlots(wanted = stuck + stale)`, reclaims first, so claims stop at the reserved slots and the remaining rows stay unclaimed for the next sweep. Abandons always run. Needed in both modes: CDC produces no second change event for a row whose status write failed, whose dispatch was rate-limited, or whose record was lost to the DLT. `recover()` runs `recoverOnce()` in `containFailure` (infrastructure `common`): an `Exception` is logged at ERROR, an interrupt is rethrown with its flag, an `Error` ends the tick |
| `OutboxRetentionScheduler.kt` | `@Component`, `@Scheduled(fixedDelay = 1h, initialDelay = 5min) purge()` → `purgeOnce()` deletes SUCCESS/FAILURE rows older than `slack.app.outbox.retention.days` (default 14) in batches of `retention.batch-size` (default 1000), up to 20 batches per tick until one comes back short; never touches PENDING/IN_PROGRESS. `purge()` wraps it in `containFailure` like `recover()`; `purgeOnce()` itself contains nothing, so an `Exception` reaches `purge()`. The file also declares the batch loop both retention purges share (2026-10-20, PR 6 review N4): `internal const val RETENTION_MAX_BATCHES_PER_TICK = 20` and `internal fun purgeInBatches(batchSize, deleteBatch: (limit: Int) -> Int): Int`, which calls `deleteBatch(batchSize)` until one call returns fewer than `batchSize` rows or after 20 calls and returns the total — the limit sent and the stop rule share one value; it catches nothing. `service/agent/AgentRetentionScheduler` runs it once per table inside its own `containFailure` and counts each batch in its own lambda for the partial total its failure line names |
| `PollingMessageProcessor.kt` | Not a `@Service` — bean from `configurations/ConsumerConfig.kt` `PoolingPublisherConfig` (`@Conditional(OnPollingConsumer)`). `@Scheduled(fixedRate = 5000) pollPending()` → `claimAndDispatch()`: `claimWithReservedSlots(wanted = batchSize)` → `findPendingMessages(limit = reserved slots)` → per-row `claim(row, now)`; no free slot means no read. Depends on the concrete `SlackMessageRelayServiceImpl` |
| `DebeziumLogTailingProcessor.kt` | Bean from `CdcPublisherConfig` (`@Conditional(OnCdcConsumer)`), constructed with `MessageOutboxRepository`, `MessageRelayService` and `Clock`. `@KafkaListener(topics = ["${slack.app.mode.cdc.topic}"], containerFactory = "concurrentKafkaListenerContainerFactory")` with `spring.json.value.default.type = ...relay.Envelope` on `consume(@Payload(required = false) envelope: Envelope?)`; a null payload (tombstone) is skipped with a DEBUG line (the hourly retention purge makes one per deleted row, so it is not a warning). Reads `payload.after` → `toOutboxMessage()`, ignores delete events and non-`PENDING` after-images, then **re-reads the row**: only a row still `PENDING` is claimed (`claim(row = current, now)`) and passed to `relayService.dispatchClaimed` on the listener thread; `IN_PROGRESS` (another owner, or a stuck row the recovery sweep owns), `SUCCESS`, `FAILURE` and a missing row are skipped. Unparseable after-image / malformed `eventId` throw `CdcRecordParseException` so the container's error handler parks the record on `<topic>-dlt` without retrying. `consume` is the one place in the relay that catches an `Error` (2026-10-19, decision #48's exception clause): spring-kafka 4.1 routes only a `RuntimeException` to the error handler and stops the whole container on an `Error` (`Stopping container due to an Error`, nothing restarts it), so `consume` logs the `Error` at ERROR with the record's partition, offset and `event_id` and rethrows it as `ListenerErrorException(cause)`. `KafkaConsumerConfiguration` classifies that wrapper as poison and not retryable, so the record goes to `<topic>-dlt` once, like a parse failure before this change, and the container goes on with the next record; for a dispatch-path `Error` the claimed row stays `IN_PROGRESS` for the sweep and the DLT copy is inert (replaying it skips the non-`PENDING` row). `consume` takes `@Header` `RECEIVED_PARTITION` / `OFFSET` (optional, defaulted for direct calls) only for that log line |
| `DebeziumOutboxMessage.kt` | Jackson model of the Debezium envelope, reduced to what the processor reads (2026-10-02): `Envelope(payload)` and `Payload(op?, after?)`, both `@JsonIgnoreProperties(ignoreUnknown = true)`. Schema, `source`, `transaction` and `ts_*` are not modelled: they change with the connector, its version and the converter, and as required fields a missing one (pre-2.x records carry no `ts_us`/`ts_ns`) failed every record into the DLT. `payload` stays required, so a converter with `schemas.enable=false` (no envelope wrapper) still fails loudly |
| `OutboxPayloadRenderer.kt` | `render(row: OutboxMessage): RenderedRow(payload: SlackEventPayload, next: OutboundEnvelope?, fallback: FallbackPlan? = null)` (`next` = the envelope the following part's row carries, `null` unless the row is a chain head). `FallbackPlan(envelope, trigger: FallbackTrigger)` with `firesOn(result)`: a `ReplaceMessage`'s `fallback` ephemeral with the row's `basicInfo` and `ANY_DEFINITIVE_FAILURE`; a `ChannelMessage` / `Ephemeral` `fallback: DirectReply` as a plain `ChannelMessage(target = recipient, content)` with `basicInfo.copy(channel = recipient.id)` and `CHANNEL_REFUSED` (`detailType` stays null, so the DM renders `SIMPLE_TEXT` and `AgentAskThreadStarter` never takes the `/ask` notice for a question; the channel must be the user id because the Slack builders post to `basicInfo.channel` and ignore `target`); `null` for every other message. `require(row.schemaVersion in OutboxSchemaVersion.SUPPORTED)` *before* decode, `Transport.valueOf(row.transport)` → `renderers[transport]` (`OutboundRenderer`), `OutboundMessageCodec.decode(row.payload)`. Bean in `SlackRequestBuilderConfiguration.outboxPayloadRenderer` mapping `Transport.SLACK` |
| `SlackMessageRelayServiceImpl.kt` | Also declares `@Component class AccessBlockedTracker` (`record(at)` keeps the latest instant the relay held a row for `isAccessBlocked()`, `lastBlockedAt(): Instant?`; in memory, per replica; read by `health/readOutboxHealth`, since a held row is invisible to every outbox count) and `ACCESS_BLOCKED_DEFER` (15 min). The service: `@Service` and `SmartLifecycle` at phase `AbstractMessageListenerContainer.DEFAULT_PHASE - 1` (spring-kafka 4.1.1: `Integer.MAX_VALUE - 100`, the `KafkaListenerEndpointRegistry` phase), so on close it stops after the scheduler and after the CDC listener containers — a record the container claims while it stops is still dispatched instead of waiting 300 s for the sweep — and before the executors; takes the `Clock` bean and `AppConfig` (`outbox.polling.stuckInProgressSeconds`). `stop()` (synchronous, so its phase adds no wait to the shutdown budget) sets `stopping`, drains the claims still queued in `relayTaskExecutor` and gives their slots back: from then on `reserveDispatchSlots` grants 0, `batchPendingMessages` and `dispatchClaimed` return without sending, and every such claim stays `IN_PROGRESS` with no send spent for another pod's sweep (stuck threshold). Only dispatches already running are left, and the executor's shutdown wait covers just those (the pool, built with `waitForTasksToCompleteOnShutdown`, takes no lifecycle pause and would otherwise start every queued claim until it is destroyed). Dispatch slots: an `AtomicInteger` starting at `relayQueueCapacity(appConfig)` (the relay queue size, `outbox.polling.batch-size`), whatever the executor's type; `reserveDispatchSlots` takes up to `wanted` atomically, `releaseDispatchSlots` gives back, capped at the capacity. `batchPendingMessages` submits `dispatchClaimed(claim)` per claim to `relayTaskExecutor: Executor` by hand (no `@Async` — self-invocation would bypass the proxy); a claim gives its slot back when a pool thread starts it, so a running dispatch holds none and the queue never holds more claims than it has room for. A `RejectedExecutionException` (the pool's `AbortPolicy`) stops the loop, gives back the remaining slots and logs the left-over `eventId`s at WARN — those rows stay `IN_PROGRESS` with no send spent for the sweep. Nothing runs on the submitting thread, which is a shared `taskScheduler` thread. `dispatchClaimed`: parse the row `eventId` (malformed → `completeClaim(FAILURE)` at once, no render, no event); a row whose `createdAt` is older than `giveUpAfterHours` → `MessagePublishFailedEvent` ("expired") + FAILURE, logged at ERROR, with no renew and no send — every reader funnels through here, so a poller tick or a CDC backlog replayed after a day-long outage cannot send an expired row; a row whose `schemaVersion` is not in `OutboxSchemaVersion.SUPPORTED` → ERROR log and return (row stays `IN_PROGRESS`, not renewed, no send spent, reclaimed by the sweep until a binary that reads it sends it or the 24 h bound ends it); `renewClaim(eventId, attempt, now)` — also `send_count + 1`; 0 means the recovery sweep took the row over while this task waited, so nothing is sent, and a DB `Exception` there (`runCatchingExceptions`) is logged and leaves the row to the sweep; render in its own `try` (throws → `MessagePublishFailedEvent`, terminal); dispatch in its own `try` (throws → logged, row stays `IN_PROGRESS`). Outcome: `isRateLimited()` → `defer` = `deferClaim(eventId, attempt, updatedAt = now + wait - stuckThreshold)` where `wait = min(retryAfter() ?: 60 s, giveUpAfterHours) + spread` (the cap keeps an absurd `Retry-After` from overflowing the `Duration`/`LocalDateTime` arithmetic outside any `try`, whatever the dispatcher's parser lets through), spread = `eventId.hashCode()` mod 2 min so rows limited together come back in different sweeps, and the resulting eligibility is capped at `createdAt + giveUpAfterHours` so a long `Retry-After` (hours) is honoured but cannot park a row beyond the 24 h bound; `isAccessBlocked()` (the dispatcher's token/scope/workspace-wide refusals) → ERROR log, `AccessBlockedTracker.record(now)` and the same `defer` with `retryAfter = ACCESS_BLOCKED_DEFER`, so the row is held, not failed, until the token is fixed or the 24 h bound ends it; `defer` takes a `reason` that leads its WARN line (rate limit vs access blocked); `isTransientExhausted()` → nothing written; anything else is terminal: a result that is `ok` first publishes `MessageDeliveredEvent(eventId, commandDetailType, messageTs)` in its own `try` (a listener failure is logged at ERROR and the status write goes on), then → `completeClaim(eventId, attempt, status, now)` inside `retryService.execute(maxAttempts = STATUS_WRITE_ATTEMPTS)` (3; the constant is `internal` and the ask lane's delivery listener reuses it), each attempt one `TransactionTemplate` transaction (the constructor takes the `PlatformTransactionManager`) that, when the CAS returned 1 and the result is a success or `isOutcomeUnknown()`, also saves the next part's row (`outboundMessagePort.toRow(next.message, next.basicInfo, the row's transport, next.continuation)`), and only when that returned 1 `publishEvent(OutboxUpdateEvent)` in a separate `try` (a listener failure is logged as such). A retried attempt whose CAS returns 0 re-reads the row (`findByIdOrNull`) and counts it as written when it already holds this status under the claim's `attempt_count`: an earlier attempt committed but its acknowledgement was lost (`TransactionSystemException`), so the outcome event is still published, and the next part is not staged again because only the attempt whose CAS returned 1 stages it (2026-10-15). A first attempt that returns 0 is never re-read. A `FAILURE` recorded without a next part (permanent refusal, render failure, the 24 h bound) logs at ERROR how many chained parts it drops (`chainedParts()`). A failed or exhausted status write is caught and logged at ERROR with `eventId` / `idempotencyKey` — no `Exception` propagates, so the CDC container does not redeliver a record whose message was already sent. `renewClaim`, `deferClaim` and the status write catch `Exception` only; an `Error` (including one from inside `RetryService`, which rethrows it unwrapped) propagates out of `dispatchClaimed`. `@TransactionalEventListener(BEFORE_COMMIT) saveOutboxMessage(OutboundMessageEnqueued)` → `outboundMessagePort.toRow` (with the payload's `continuation`) + `save` |

## For AI Agents

### Working In This Directory
- **Only the current claim owner dispatches.** Every claim and reclaim increments `attempt_count`; the
  winner carries that number in its `OutboxClaim`. `dispatchClaimed` renews the claim with it right before
  sending (`renewClaim` also restarts the stuck clock; the sweep cannot take the row mid-send only because a
  whole dispatch is time-bounded well below `stuckInProgressSeconds` (300 s) — the Slack HTTP bound in
  `infrastructure/.../impl/command/AGENTS.md` plus the SQL wait in the budget bullet below; lengthening a send past
  the threshold reopens the double send. Binding refuses a threshold below twice `RELAY_RECORD_TIME_BOUND`
  (`AppConfig.Outbox.Polling.MIN_STUCK_IN_PROGRESS_SECONDS`, 92 s today), so the floor follows the bound) and writes
  the terminal status with it, so a task that waited in the executor past the stuck threshold neither sends
  nor overwrites the new owner's `SUCCESS` with `FAILURE`. The CDC reader never touches an `IN_PROGRESS`
  row: stuck rows belong to `OutboxRecoveryScheduler` alone. The two readers are exclusive by
  `OnPollingConsumer` / `OnCdcConsumer` (`configurations/conditions/Conditions.kt`).
- **One clock for `updated_at`.** Every native write takes `now` from the injected `Clock` (the context's
  single `Clock` bean), the same clock the sweep's cutoffs, the retention purge and `OutboxHealthIndicator`
  use, and the one `@CreationTimestamp`/`@UpdateTimestamp` follow (JVM time). Never reintroduce
  `CURRENT_TIMESTAMP`: the DB session zone differs from the JVM zone in the shipped manifests.
- **The row `eventId` is the identity.** Both readers parse it up front and key every `OutboxUpdateEvent`
  on it; the payload `eventId` the renderer mints is throwaway. A malformed row id is deterministic: the
  polling/sweep path completes the claim as `FAILURE` at once, the CDC path sends the record to the DLT.
- **Render is not retried; dispatch is.** A codec / schema failure surfaces as `MessagePublishFailedEvent`
  immediately; quick retries live inside `MessageDispatcher`, slow ones in the recovery sweep. A `schemaVersion` outside
  `OutboxSchemaVersion.SUPPORTED` is held unsent before `renewClaim`, never failed, so an older binary that has
  this check leaves a newer release's rows for that release; `main`, from before the check, fails them at render — when adding a version, extend `SUPPORTED` and keep
  `decode` able to read the old shape.
- **`saveOutboxMessage` is `BEFORE_COMMIT`.** The row commits atomically with the command's own writes,
  which is why `SlackOutboundStager` must be called inside a `@Transactional` handler
  (`SlackInteractionHandlerImpl`, `SlackMentionEventHandlerImpl`, the slash services). With no active
  transaction the event is simply not delivered (`fallbackExecution` defaults to false) and nothing
  reaches the outbox.
- **Three dispatch outcomes, one budget in real sends.** Done (success or permanent failure) is written
  and published. Rate-limited and transient-exhausted rows, and dispatches that threw, stay `IN_PROGRESS`
  for the sweep. `attempt_count` is only the ownership token; the abandon budget is `send_count`, which
  `renewClaim` raises right before a send and `deferClaim` takes back for a rate limit. So 429s and claims
  taken over in the executor queue never abandon a row; `outbox.polling.maxSends` transient sends do, and
  the 24 h `created_at` bound stops everything: the sweep abandons stuck rows and stale `PENDING` rows past it
  (`abandonStuck` / `abandonPending`), and `dispatchClaimed` fails any other claim past it before sending. A resend can post twice in Slack (no idempotency key
  there), which beats a row that never resolves.
- **Per-record time budget (each number lives in one place).** On the CDC listener thread one PENDING record
  costs `findById` + `claimPending`, then `dispatchClaimed`: `renewClaim`, render + dispatch, and `completeClaim`
  (up to `STATUS_WRITE_ATTEMPTS` = 3, backoff 0.1 + 0.2 s + 10 + 20 ms jitter). The Slack HTTP bound (render's
  profile lookup plus the dispatch retries) is derived **only** in
  `infrastructure/src/main/kotlin/dev/notypie/impl/command/AGENTS.md`; do not copy its total here or in the wiki.
  The SQL side is this file's: with the Hikari pool exhausted each statement attempt can wait a full
  `connection-timeout` (5 s in every profile, prod via `SQL_PROD_CONNECTION_TIMEOUT`), so find + claim + renew +
  3 completes + the lost-acknowledgement re-read on attempts 2 and 3 add up to 8 × `connection-timeout` + 0.33 s (≈ 40 s). Consequences: (a) with a starved pool one
  record can overrun its 60 s share of `max.poll.interval.ms` 300 s / `max-poll-records` 5 — the result is a
  rebalance and redelivery, not a double send, because the listener claims only `PENDING`; (b) the renew →
  complete window (HTTP bound + 5 × `connection-timeout` + 0.33 s: three completes and two re-reads) must stay well below
  `stuck-in-progress-seconds` (300 s), or the sweep reclaims a row mid-send. Raising `connection-timeout` or
  `STATUS_WRITE_ATTEMPTS` means redoing (b). An `AGENT_ASK_QUESTION` row that Slack accepted also runs the ask
  lane's delivery listener inside that window, before the status write (2026-10-13): normally two more connection
  acquisitions (the outbox re-read and the ledger insert), plus one for the `REQUIRES_NEW` overload notice when the
  turn executor rejects a first delivery (a delivery that found its row hands over with `offer` and writes none).
  Its worst case is ten: the read retried three times, the insert retried three times with a read of the stored
  row after a duplicate, and the notice — there the notice is the unleased fallback's, after all three records
  failed; each of the two retry loops has the status
  write's backoff, 0.66 s together. For such a row (a) becomes 18 × `connection-timeout` + 0.99 s (≈ 91 s) and the
  SQL side of (b) 15 × `connection-timeout` + 0.99 s (≈ 76 s) — still a rebalance at worst and still well below the
  stuck threshold. `STATUS_WRITE_ATTEMPTS` is shared with that listener on purpose, so raising it lengthens both
  loops. (c) Shutdown waits one record with a healthy pool: `RELAY_RECORD_TIME_BOUND`
  (in `SlackMessageRelayServiceImpl.kt`) is computed from `RestClientRequester.DEFAULT_READ_TIMEOUT` (the profile
  lookup's whole-call bound), `SLACK_DISPATCH_TIME_BOUND` and `retryTimeBound` of the status write, and
  `configurations/AsyncConfig.kt`'s `RECORD_SHUTDOWN_WAIT` rounds it up for the Kafka listener phase and the relay
  executor's wait; change any of those timeouts and the shutdown budget follows (`ShutdownBudgetTest`). The status
  write's attempts count as zero time there (`attemptTimeout = Duration.ZERO`): connection acquisition, a row-lock
  wait, the commit and a chained part's insert are outside the bound, and so are the ask listener's database time
  and its retry backoff (decided 2026-10-13: the bound and `RECORD_SHUTDOWN_WAIT` stay at 46 s). When they take
  long, the wait ends first, the EntityManagerFactory closes under the dispatch, the status is never written and the
  sweep resends a post that may already be on Slack; for an `/ask` question the ledger row is keyed by the outbox
  event id, so the second delivery finds it and the question still gets one turn.
- **A chained reply's next part is staged only by the CAS that recorded the current one** (2026-10-02). The
  insert shares the status write's transaction and runs only when `completeClaim` returned 1, so the parts post
  strictly one after another in both reader modes (the new `PENDING` row is an ordinary row to the poller and a
  CDC create event to the listener), a retried status write (whose earlier commit landed) or a taken-over claim
  stages nothing, and a failed insert rolls the `SUCCESS` back to `IN_PROGRESS` for the sweep. A deferred row
  (rate limit, access blocked) keeps its continuation in its payload; outcome unknown records `FAILURE` and still
  stages the next part (the request most likely reached Slack; stopping would silently drop every later part,
  while going on at worst leaves one labelled `(k/n)` gap); any other `FAILURE` drops the rest with an ERROR
  log, as does the sweep's abandon.
- **A slash reply in a channel Slack refuses stages its direct reply through the same CAS** (B-2, 2026-10-14). A
  `ChannelMessage` / `Ephemeral` with a `fallback` (schema V6 when written before 2026-10-20, V7 like every row since — `docs/wiki/decisions.md` #50; every slash reply, producers listed in
  `domain/.../command/outbound/AGENTS.md`) whose definitive `FAILURE` is `isChannelRefused()` (`not_in_channel`,
  `user_not_in_channel`, `no_permission`, `channel_not_found`, `is_archived`, `access_denied`, `ekm_access_denied`,
  `restricted_action*`)
  is completed with the DM as its `next` row. Only after this owner's status write committed (`complete` returned
  `true`) does it log the WARN `Slack refused the channel of eventId=… (<code>); staged its direct reply to <user>`
  (the replace wording reads `…; staged its fallback`), so a lost CAS or a failed write never claims a DM. Any other definitive failure (`invalid_blocks`, …) stages nothing; rate-limited,
  access-blocked, transient and outcome-unknown are untouched, so the time budget and the write-ahead
  `MessageDeliveredEvent` order do not move. A refused `/ask` question publishes no `MessageDeliveredEvent`, so no
  ledger row and no turn: the DM notice is its only effect. A FAILURE that stages a fallback still logs the
  chain parts it drops (`complete(…, dropsChain = true)`). The DM row is a plain row with no fallback of its own
  (V2 when written before 2026-10-20, V7 like every row since — `docs/wiki/decisions.md` #50), delivered on the next poll or its CDC insert event.
- **A refused replace-original stages its fallback through the same CAS** (2026-10-08). A row whose payload is a
  `ReplaceMessage` with a `fallback` (schema V4 when written before 2026-10-20, V7 since; the `/meetup list` close) that ends in a definitive `FAILURE` (not
  rate-limited, not access-blocked, not transient, not outcome-unknown — typically Slack's `expired_url` /
  `used_url` for a handle past its ~30 min / five uses) is completed with the fallback ephemeral as its `next`
  row and a WARN naming the event id and the error, so the host still gets the confirmation exactly once. A
  successful or outcome-unknown replace stages no fallback (an unknown outcome most likely replaced the list). Runbook: with the CDC connector stuck, every later part waits for the
  stale-`PENDING` sweep on its own (`created_at` + `stuck-in-progress-seconds` 300 s, sweep every 60 s), because
  its row exists only after the previous part succeeded and no change event arrives — an n-part reply takes
  about n × 5–6 min (an 8-part AI answer ≈ 40–48 min) where unchained parts went out in one sweep. Polling mode
  (5 s) is unaffected. Restore the connector first.
- **An outcome event is published only by the owner that recorded it.** `completeClaim` returning 0 means
  another owner has the row and will publish its own outcome — unless a retried status write finds the row already
  in this status under the claim's `attempt_count`, which only this owner's earlier, unacknowledged commit can
  produce (claims and reclaims bump `attempt_count`; `abandonStuck` bumps it too (2026-10-19), so an abandoned row
  never reads as the old owner's write even if the stuck floor were broken). `MessageDeliveredEvent` is not an outcome event and
  has no such guarantee.
- **`MessageDeliveredEvent` is the write-ahead hook, published before the status write** (2026-10-13). It says
  "the transport accepted the message; the row is not yet recorded", is published for every `ok` dispatch of any
  type, synchronously on the dispatching thread (CDC listener or polling executor), and may come from an owner
  whose `completeClaim` then returns 0. Its one listener is `service/agent/AgentAskThreadStarter`, which acts only
  on `AGENT_ASK_QUESTION` with a ts: it re-reads the outbox row, writes the `agent_ask_turn` ledger row and hands
  the turn to the turn executor — all before `SUCCESS` is written, so a crash in between leaves either an
  `IN_PROGRESS` outbox row (re-posted by the sweep) or a ledger row (resumed by the ask sweep), never a posted
  question with neither. The order is the point: do not move the publish after `writeTerminal`, fold it into
  `MessagePublishSuccessEvent`, or dispatch it asynchronously. A listener that throws is logged at ERROR here and
  the status write proceeds; by design the ask listener lets one failure through, an outbox read that failed on
  every attempt, and then that question gets no turn.
- **`MessagePublishSuccessEvent` has one downstream consumer:** `service/standup/StandupSummaryService` swaps its
  `outbox:<eventId>` marker for `messageTs` (type `STANDUP_SUMMARY`) and skips every other type. Keep `messageTs`
  and `commandDetailType` (from the dispatch output, i.e. the rendered payload) populated on a successful result:
  both events carry them. Nothing else listens to `OutboxUpdateEvent`s.
- The `Envelope` FQN is hardcoded in the `@KafkaListener` properties; moving or renaming the class
  breaks CDC deserialization at runtime with no compile error. `relayTaskExecutor` is the dedicated bounded
  `@Qualifier("relayTaskExecutor")` bean in `configurations/AsyncConfig.kt`; `Error`s deliberately propagate to
  its uncaught handler, `RELAY_UNCAUGHT_ERROR_LOGGER`, which every pool thread carries: one ERROR line through
  logback naming the thread (not the `eventId`, which only the stuck `IN_PROGRESS` row shows), then the thread dies
  and the pool replaces it. Every catch on the dispatch path names `Exception`, and `RetryService` unwraps an `Error`
  that Spring's `RetryTemplate` wrapped in `RetryException` (2026-10-15): the row stays `IN_PROGRESS` for the
  sweep, bounded by `maxSends` and `giveUpAfterHours`. On the CDC path the same `Error` would stop the listener
  container, so `DebeziumLogTailingProcessor.consume` translates it (row above).
- **Never run relay work on a scheduler thread.** The poller and the sweep share `taskScheduler` with every
  other `@Scheduled` job, so a dispatch there stalls reminders, standups and the CVE lane. Claim through
  `claimWithReservedSlots` and let a rejected claim go back to the sweep; do not reintroduce `CallerRunsPolicy`.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.service.relay.*'
```
Specs under `application/src/test/kotlin/dev/notypie/application/service/relay/` (see that directory's
`AGENTS.md`). Fixtures from `dev.notypie.application.outbox` (application testFixtures):
`createPollingProcessorFixture(clock)` (its default relay mock grants every reservation), `createRelayService(outboxRepository, ...)` (real service, real
`RetryService`, inline `Executor` unless one is passed, fixed clock; its slots are `appConfig`'s batch size), `stubClaimLifecycle(renewed, completed,
deferred)`, `createOutboxRow(attemptCount, sendCount)`, `createFixedUtcClock`, `DEFAULT_TEST_NOW`, and for the
H2 scenario `createOutboxJpaContext`, `MutableClock`, `ScriptedMessageDispatcher`, `QueuedExecutor`; from `dev.notypie.application.service.relay`: `createCdcEnvelope`,
`createOutboxAfterImage`, `createCdcConsumerRecord`. Codec / schema / SQL behaviour is covered by
`infrastructure/src/test/kotlin/dev/notypie/repository/outbox/`.

### Common Patterns
- Reader beans are plain classes wired in `ConsumerConfig.kt`, not component-scanned — so a profile
  or condition decides which one exists.
- `runCatching { UUID.fromString(...) }.getOrElse { log; return }` for row-id parsing.
- `retryService.execute(action = {...}, maxAttempts = n)` around the terminal status write, which runs in its own transaction per attempt, outside any caller transaction. Never around `saveOutboxMessage`'s `save`: it runs inside the command's transaction, a failure there already marks it rollback-only, and the INSERT only executes at the commit flush anyway.
- Error logs carry `eventId` and `idempotencyKey` — keep that shape for outbox debugging.

## Dependencies

### Internal
- `infrastructure/repository/outbox/` — `MessageOutboxRepository` (`findPendingMessages`, `claimPending`,
  `findStuckInProgress`, `findStalePending`, `reclaimStuck`, `abandonStuck`, `abandonPending`, `renewClaim`, `deferClaim`,
  `completeClaim`, `deleteTerminalOlderThan`, `findById`, `save`), `OutboundMessagePort`, `OutboundMessageCodec`,
  `Transport`, `OutboxSchemaVersion`, `schema/OutboxMessage` (`attemptCount`, `sendCount`, `toOutboxMessage`),
  `schema/MessageStatus`, `dto/OutboxUpdateEvent` + `MessagePublishFailedEvent` /
  `MessagePublishSuccessEvent` + `toOutboxUpdateEvent`, `dto/MessageDeliveredEvent`
- `infrastructure/impl/command/event/` — `MessageDispatcher`, `SlackEventPayload`, `OutboundMessageEnqueued`
- `infrastructure/impl/command/` — `OutboundRenderer`, `isRateLimited()` / `isAccessBlocked()` / `isTransientExhausted()` / `isChannelRefused()` /
  `retryAfter()` on the dispatcher's `CommandOutput`; `infrastructure/impl/retry/RetryService`
- `application/configurations/` — `ConsumerConfig.kt`, `SlackRequestBuilderConfiguration.kt`,
  `AsyncConfig.kt`, `AppConfig.outbox.polling.{batchSize, stuckInProgressSeconds, giveUpAfterHours, maxSends}`,
  the `Clock` bean from `security/SlackRequestVerificationConfiguration`
- `application/service/standup/StandupSummaryService` — consumer of `MessagePublishSuccessEvent`;
  `application/service/agent/AgentAskThreadStarter` — consumer of `MessageDeliveredEvent`, user of `STATUS_WRITE_ATTEMPTS`;
  `application/service/agent/AgentRetentionScheduler` — user of `purgeInBatches`

### External
Spring scheduling, Spring Kafka (`@KafkaListener`), Spring transaction events, Jackson, kotlin-logging.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
