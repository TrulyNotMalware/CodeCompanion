<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-02 -->

# test/kotlin/dev/notypie/application/service/agent

## Purpose
Spec for `AgentConverseService.handleAgentConverse`: one AI turn against the sidecar `AgentGateway`, session
resumption keyed by `channel:thread:requester`, transport-neutral reply staging, `agent_turn_history` auditing, Micrometer
counters, and optional MCP scoped-token minting.

## Key Files
| File | Description |
|------|-------------|
| `AgentConverseServiceTest.kt` | Builds the service via a local `buildService(...)` with MockK `AgentGateway`, relaxed session/history repositories and `EventPublisher`, a capturing `OutboundMessageStager`, `SimpleMeterRegistry`, a stubbed `PlatformTransactionManager`, `createFixedUtcClock(2026-07-03T12:00)`, and an optional `ScopedTurnTokenCodec`. `Completed` → `AgentTurnRequest` keyed `channel:threadTs:publisherId` with the stored provider session id, `scopedToken == null` while no codec is wired, `appendSystemPrompt` contains `<@user> (name)`, `#channel`, the date, and "mrkdwn"; new session id saved; `ChannelMessage` typed `AGENT_CONVERSE` into the thread with `RESPONSE_HEADLINE`; `AgentTurnRecord` `COMPLETED` with token counts; `METRIC_TURNS`/`METRIC_TOKENS` counters. Empty `finalText` → `EMPTY_RESPONSE_MESSAGE`, no session save. A ~32,000-character answer → several `ChannelMessage`s in the same thread headlined `RESPONSE_HEADLINE (i/n)`, one publish each, every body within `MESSAGE_BODY_BUDGET` and rejoining to the answer; a 100,000-character answer → bodies totalling at most `MAX_ANSWER_LENGTH`, the last ending in `TRUNCATION_MARKER`; `capAnswer` keeps a surrogate pair whole at the cut and leaves a short answer unchanged. `Busy` → `Ephemeral` `BUSY_MESSAGE` to the requester, audited `BUSY`. An executor that rejects the turn → `Ephemeral` `OVERLOADED_MESSAGE` to the requester, no gateway call, `METRIC_TURNS{outcome=rejected}` = 1 (`buildService` takes a `turnExecutor`, default a caller-runs `Executor`). `Failed(code)` → `FAILURE_MESSAGE` in-thread, audited `FAILED` with `errorCode`. No `threadId` → session key `channel:publisherId`, reply un-threaded; a blank `threadId` gives the same key. Two requesters in one thread get separate keys and the second does not resume the first's provider session. Names render as `<@id> (display name "…")` / `<#id> (channel name "…")`. Blank names → bare `<@id>` / `<#id>`; names with newlines, NUL, U+202E and >64 chars stay on their own bullet line, carry no control characters and are capped at `MAX_CONTEXT_NAME_LENGTH`; a name with `"`, backticks and a backslash stays inside one pair of quotes (all become `'`); an emoji straddling the 64-unit cap is dropped whole instead of leaving a lone surrogate; names that are only control characters fall back to the bare mentions. Codec wired → `scopedToken` verifies back to `publisherId`, `channel:threadTs:publisherId`, `turnId == idempotencyKey`. One case runs on `createOutboxJpaContext()`: a rejecting executor and an `EventPublisher` that saves a real outbox row, invoked from `afterCompletion` of a committed `JpaTransactionManager` transaction → the notice row is committed (fails with a REQUIRED template) An answer with `<!channel>`, `<!here|here>`, `<@U1>` and a link → only the two broadcasts are escaped. |

## For AI Agents

### Working In This Directory
- `stubTransactionManager()` returns a relaxed `TransactionStatus` with `commit`/`rollback` as `just Runs`, so
  rollback-only behaviour is not observable here — assert on repository calls instead.
- `stagerCapturing(slot)` returns a relaxed `CommandEvent` mock; assert the captured `OutboundMessage`, not the
  event, and use `verify { eventPublisher.publishEvent(events = any()) }` for the publish.
- The MCP case builds a real `ScopedTurnTokenCodec` on the system clock. Do not pin it to `fixedNow`
  (2026-07-03): the token would already be expired when `verify` runs.
- The message constants (`RESPONSE_HEADLINE`, `EMPTY_RESPONSE_MESSAGE`, `BUSY_MESSAGE`, `FAILURE_MESSAGE`,
  `METRIC_*`) are read from the companion object; rename them in both places.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.service.agent.*'
```
Fixtures used: `testFixtures/.../application/outbox/OutboxTestFixtures.kt` (`createFixedUtcClock`); `domain`
testFixtures `command/CommandDomainInputCreator.kt` (`createAgentConverseRequestEvent`, `createCommandBasicInfo`)
and `Constants.kt` (`TEST_THREAD_TS`, `TEST_USER_NAME`, `TEST_CHANNEL_NAME`).

### Common Patterns
- One `given` per gateway outcome (`Completed`, empty, `Busy`, `Failed`, no thread, MCP on), a single `when`
  that calls `handleAgentConverse`, and several `then`s each reading one captured slot.
- `slot<AgentTurnRequest>()` on `gateway.converse(request = capture(...))` is how the outbound prompt is inspected.

## Dependencies

### Internal
- `application/service/agent/AgentConverseService.kt`, `application/security/mcp/ScopedTurnTokenCodec`
- `infrastructure/impl/agent/AgentGateway`, `AgentTurnRequest`, `AgentTurnResult`
- `infrastructure/repository/agent/*`, `domain/command/outbound/*`, `domain/command/entity/event/*`

### External
Micrometer `SimpleMeterRegistry`, Spring `PlatformTransactionManager`, MockK, Kotest.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
