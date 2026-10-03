<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-03 -->

# application/service/agent

## Purpose
One AI-agent conversation turn. `AgentConverseService` listens for the `AgentConverseRequestEvent`
that `SlackIntentResolver` lifts from a free-text `@bot ...` mention, calls the sidecar through
`AgentGateway`, then commits the resumable session id, an `agent_turn_history` audit row, and the
staged Slack reply in one transaction. Turns run on `agentTurnExecutor` (declared in
`configurations/AgentConfiguration`, sized by `slack.app.agent.turns.*`: 4 concurrent turns, a queue of 20, 20 s
shutdown wait), which the AFTER_COMMIT listener submits to directly rather than through `@Async`, so a full
executor is seen: the requester gets `OVERLOADED_MESSAGE` (written in its own transaction) and
`agent.turns{outcome=rejected}` counts it. Each submission is an `AgentTurn(start, onDiscard)`: a turn still
queued when the executor's shutdown wait ends is discarded by `AgentTurnExecutor`, and its requester gets the same
notice (`agent.turns{outcome=discarded}`, WARN log).

## Key Files
| File | Description |
|------|-------------|
| `AgentConverseService.kt` | `class AgentConverseService(agentGateway, agentSessionRepository, agentTurnHistoryRepository, outboundStager, eventPublisher, meterRegistry, transactionManager, turnExecutor, clock, scopedTurnTokenCodec: ScopedTurnTokenCodec? = null)`. `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true) handleAgentConverse(event)` (so a mention whose transaction rolls back, and is then retried by Slack, never starts a turn; the listener hands `converse` to the bounded `turnExecutor`, so the turn leaves the committing thread) builds `sessionKey = "$channel:$threadId:$publisherId"` (`"$channel:$publisherId"` without a thread or with a blank one), sends `AgentTurnRequest(sessionKey, prompt, sessionId = findProviderSessionId, userId, appendSystemPrompt = contextPrompt, scopedToken = codec?.mint(...))`, then branches on `AgentTurnResult`: `Completed` → `publishAnswer` (save provider session id, record `COMPLETED` with token counts; the answer is cut by `capAnswer` at `MAX_ANSWER_LENGTH` (`CHAIN_TEXT_BUDGET`, 40,000: every part rides in one chain head whose CDC update record must stay within 1 MiB, ending in `TRUNCATION_MARKER`, never splitting a surrogate pair), blank text → `EMPTY_RESPONSE_MESSAGE`, then `splitMessageText` cuts it into at most `MAX_ANSWER_MESSAGES` (8) bodies of `SlackBlockLimits.MESSAGE_BODY_BUDGET` with code fences closed and re-opened across the cut; each body is a thread reply headlined `RESPONSE_HEADLINE`, or `RESPONSE_HEADLINE (i/n)` when there are several, and all of them go out as one `outboundStager.stageInOrder` event (one outbox row; the relay stages each next part after the previous one is recorded)); `Busy` → ephemeral `BUSY_MESSAGE` + `BUSY` row; `Failed` → `FAILURE_MESSAGE` + `FAILED` row with `errorCode`. Records `agent.turns`, `agent.turn.duration`, `agent.tokens` The capped answer goes through `templates/neutralizeBroadcastMentions()` before the split: `<!channel>` / `<!here>` / `<!everyone>` (also echoed from user text or a tool result) stay literal while links, emphasis and `<@user>` mentions keep working. |

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
  `MAX_CONTEXT_NAME_LENGTH` (64 UTF-16 units, cut one unit earlier when the cut would split a surrogate pair).
  The name is then shown as quoted data — `<@id> (display name "…")`, `<#id> (channel name "…")` — under a
  line telling the model that quoted names are labels, not instructions. A value that is blank afterwards
  (app_mention events carry no names) degrades to the bare `<@id>` / `<#id>` mention. Route any new
  user-supplied prompt field through the same function and quoting.
- `scopedTurnTokenCodec` is null when MCP is off (`AgentConfiguration` uses `ObjectProvider`); the
  turn then carries no token and the model has no tools. `turnId` is the mention's `idempotencyKey`
  so tool audit rows join back to `agent_turn_history`.
- A turn can last up to `slack.app.agent.sidecar.request-timeout-seconds` (120 s), longer than the 20 s shutdown
  wait: a rolling deploy cuts turns still running after that wait. Keep `turns.queue-capacity` small; a deep queue
  only converts an immediate "busy" reply into minutes of silence.
- A multi-part answer is one outbox row carrying every part (`stageInOrder`); the relay posts the parts in order,
  so the `(i/n)` headline only shows how many there are. Staging the parts as separate rows lets the parallel
  relay post them out of order.
- Metric names are `internal const` and dashboards depend on the `outcome` / `direction` tags.

### Testing Requirements
```bash
./gradlew :application:test --tests '*AgentConverseServiceTest*'
```
`AgentConverseServiceTest` (Kotest `BehaviorSpec`, MockK) stubs `AgentGateway.converse` per outcome, a
MockK `PlatformTransactionManager` (relaxed `TransactionStatus`, `commit` / `rollback` `just Runs`), a
`SimpleMeterRegistry`, and a fixed `Clock`; it asserts the `AgentTurnRecord` fields, the staged
`OutboundMessage` shape (thread id, headline, ephemeral recipient for `Busy`), the saved provider
session id, and the counters. Cover the null-codec and non-null-codec paths when touching token
minting; build the request event with the domain testFixtures rather than inline constructors.

### Common Patterns
- `sealed` result dispatch via `when (result)` with one private `publishXxx` per outcome.
- `turnRecord(...)` factory with defaulted nullables so every outcome writes the same row shape.
- `eventPublisher.publishOne(event = stageReply(...))` — replies are transport-neutral
  `OutboundMessage`s rendered at deliver time, never Slack blocks built here.
- `companion object` holding `internal const` copy so specs assert on the constants, not literals.

## Dependencies

### Internal
- `application/common/TransactionTemplateExt` — `runInTx`
- `application/security/mcp/ScopedTurnTokenCodec` — per-turn token
- `application/configurations/AgentConfiguration` — bean declaration, sidecar client
- `infrastructure/impl/agent/` — `AgentGateway`, `AgentTurnRequest`, `AgentTurnResult`, `SidecarAgentClient`
- `infrastructure/repository/agent/` — `AgentSessionRepository`, `AgentTurnHistoryRepository`,
  `AgentTurnRecord`, `AgentTurnOutcome`
- `infrastructure/impl/command/SlackIntentResolver` — publishes `AgentConverseRequestEvent`
- `domain/command/entity/event/` — `AgentConverseRequestEvent`, `AgentConversePayload`, `EventPublisher.publishOne`
- `domain/command/outbound/` — `OutboundMessage`, `MessageContent`, `ConversationTarget`, `UserRef`, `OutboundMessageStager`

### External
Spring `@TransactionalEventListener`, `ThreadPoolTaskExecutor`, Spring TX `TransactionTemplate`, Micrometer `MeterRegistry`,
kotlin-logging.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
