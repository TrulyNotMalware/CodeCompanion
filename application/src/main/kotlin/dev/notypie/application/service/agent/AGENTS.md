<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-01 -->

# application/service/agent

## Purpose
One AI-agent conversation turn. `AgentConverseService` listens for the `AgentConverseRequestEvent`
that `SlackIntentResolver` lifts from a free-text `@bot ...` mention, calls the sidecar through
`AgentGateway`, then commits the resumable session id, an `agent_turn_history` audit row, and the
staged Slack reply in one transaction. Turns run on `agentTurnExecutor` (declared in
`configurations/AgentConfiguration`, sized by `slack.app.agent.turns.*`: 4 concurrent turns, a queue of 20, 30 s
shutdown wait), which the AFTER_COMMIT listener submits to directly rather than through `@Async`, so a full
executor is seen: the requester gets `OVERLOADED_MESSAGE` at once and `agent.turns{outcome=rejected}` counts it.

## Key Files
| File | Description |
|------|-------------|
| `AgentConverseService.kt` | `class AgentConverseService(agentGateway, agentSessionRepository, agentTurnHistoryRepository, outboundStager, eventPublisher, meterRegistry, transactionManager, turnExecutor, clock, scopedTurnTokenCodec: ScopedTurnTokenCodec? = null)`. `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true) handleAgentConverse(event)` (so a mention whose transaction rolls back, and is then retried by Slack, never starts a turn; the class-level `@Async` still moves the turn off the committing thread) builds `sessionKey = "$channel:${threadId ?: publisherId}"`, sends `AgentTurnRequest(sessionKey, prompt, sessionId = findProviderSessionId, userId, appendSystemPrompt = contextPrompt, scopedToken = codec?.mint(...))`, then branches on `AgentTurnResult`: `Completed` → `publishAnswer` (save provider session id, record `COMPLETED` with token counts, thread reply headlined `RESPONSE_HEADLINE`, blank text → `EMPTY_RESPONSE_MESSAGE`); `Busy` → ephemeral `BUSY_MESSAGE` + `BUSY` row; `Failed` → `FAILURE_MESSAGE` + `FAILED` row with `errorCode`. Records `agent.turns`, `agent.turn.duration`, `agent.tokens` |

## For AI Agents

### Working In This Directory
- The listener runs on `threadPoolTaskExecutor` (10 threads, queue 10 000) with no ambient
  transaction, so every outcome's writes go through `transactionTemplate.runInTx { ... }`. That
  boundary is what lets `SlackMessageRelayServiceImpl.saveOutboxMessage` (`BEFORE_COMMIT`) persist the
  staged reply; publishing outside it silently loses the message. `runInTx` swallows into a
  `Result` — failures are logged with `sessionKey` and `idempotencyKey`, never rethrown.
- `stageReply` is `checkNotNull` on `outboundStager.stage(...)`: a null stage means the reply would
  vanish, so the turn's transaction must fail instead. Keep that check when adding reply kinds.
- Session continuity is two keys: `sessionKey` (sidecar workspace, derived from thread or requester)
  and the provider session id stored in `agent_session`, echoed back as `sessionId` on the next turn.
  Change the `sessionKey` formula and every open conversation loses its context.
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
- A turn can last up to `slack.app.agent.sidecar.request-timeout-seconds` (120 s), longer than the 30 s shutdown
  wait: a rolling deploy cuts turns still running after that wait. Keep `turns.queue-capacity` small; a deep queue
  only converts an immediate "busy" reply into minutes of silence.
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
Spring `@Async` / `@TransactionalEventListener`, Spring TX `TransactionTemplate`, Micrometer `MeterRegistry`,
kotlin-logging.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
