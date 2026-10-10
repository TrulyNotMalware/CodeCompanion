<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-04-28 | Updated: 2026-10-07 -->

# application/common

## Purpose
Framework-light helpers shared across the `application` module: deterministic idempotency-key
derivation, Slack slash-command form parsing into the transport-neutral `InboundCommand`, and a
`TransactionTemplate` wrapper that gives scheduler/async code a `Result`-typed transaction boundary, and a
factory for a template that runs work outside the caller's transaction.
Nothing here is a Spring bean; every helper is a top-level function or `object` usable without an
application context.

## Key Files
| File | Description |
|------|-------------|
| `IdempotencyCreator.kt` | `object IdempotencyCreator` with two `create(data, currentTimeMillis = System.currentTimeMillis()): UUID` overloads (`String` / `IdempotencyData`). Seed is `currentTimeMillis / 1000`; the key is `UUID.nameUUIDFromBytes("$data|$seed")`. `IdempotencyDataSerializer` + `DefaultIdempotencyDataSerializer` (`jsonMapper` bytes → SHA-256 hex) turn an `IdempotencyData` into the `String` input |
| `SlackRequestParser.kt` | `parseRequestBodyData(data: Map<String, String>): SlashCommandRequestBody` (Jackson `convertValue`); `parseRequestBodyData(headers, data): Pair<SlashCommandRequestBody, InboundCommand>` adds `toInboundCommand()` — `headers` is accepted but unused; `Map<String, Any>.convert<T>()` is a reified `convertValue` helper with no current call sites |
| `TransactionTemplateExt.kt` | `inline fun <T> TransactionTemplate.runInTx(crossinline action: () -> T): Result<T>` — `runCatching` inside `execute` with `setRollbackOnly()` on failure, and an outer `runCatching` around `execute` so a commit-time failure (flush, a `BEFORE_COMMIT` listener such as the outbox write) is also returned as `Result.failure` instead of escaping past the caller's `.onFailure` (2026-10-01). `internal fun detachedTemplate(transactionManager): TransactionTemplate` — `PROPAGATION_NOT_SUPPORTED`, the counterpart of `service/meeting`'s `isolatedWriteTemplate` (`REQUIRES_NEW`): the caller's transaction is suspended for the block, so a `@Transactional` repository call inside it runs in its own transaction and its failure cannot mark the caller's rollback-only. Used by `StandupRoutineOpsService` (stop's admin lookup), `AgentUsageReportService` and `OpsStatusService` (report rendering). The block takes a second pooled connection while the caller still holds one |

## For AI Agents

### Working In This Directory
- `IdempotencyCreator.create(data = commandData)` is the single way to derive a dedup key from an
  `InboundCommand`. Every inbound entry point uses it: `MeetingServiceImpl`, `StandupSlashServiceImpl`,
  `CveSubscriptionSlashServiceImpl`, `CveQuerySlashServiceImpl`, `SlackMentionEventHandlerImpl`,
  `SlackInteractionHandlerImpl`. Do not roll ad-hoc UUIDs.
- The 1-second window (`DEFAULT_IDEMPOTENCY_TIME_WINDOW_MS`) means identical data inside the same
  second yields the same UUID — intended so a retried Slack delivery collapses onto one key. Changing
  the window changes downstream retry semantics (outbox, Kafka, Slack replay); do it deliberately.
- Keys hash the Jackson serialization of the command, not `java.io.Serializable` bytes, so nested
  payload types (`InboundInteraction`, `@JvmInline TriggerHandle`) need no `Serializable` marker. Keep
  `jsonMapper`'s `ORDER_MAP_ENTRIES_BY_KEYS` on — key stability depends on deterministic output.
- `parseRequestBodyData(headers, data)` is the canonical slash entry point for both transports:
  `controllers/SlashCommandController` (HTTP form params) and `socket/SocketModeReceiver.handleSlash`
  (Socket Mode envelope). It returns the wire DTO and the `InboundCommand` together so nothing is
  deserialized twice.
- `runInTx` is for code that runs with no ambient transaction — the scheduling services driven by the
  `*Scheduler` wrappers (`StandupSchedulingService`, `StandupSummaryService`,
  `DailyAgendaSchedulingService`, `MeetingReminderSchedulingService`), the `@Scheduled`
  `CveNotificationDispatcher`, and `AgentConverseService` (whose turns run on `agentTurnExecutor`). It gives the outbox's
  `BEFORE_COMMIT` listener a transaction to bind to on that worker thread through an explicit boundary,
  instead of relying on `@Transactional` proxying of async or scheduled entry points. A failed `action`
  marks the transaction rollback-only and comes back as `Result.failure` — nothing is rethrown, so
  callers must inspect the `Result`.
- These helpers use the shared `dev.notypie.common.jsonMapper` (Jackson 3,
  `tools.jackson.databind.json.JsonMapper`). Do not add another mapper here; the only separately
  configured instances in `:application` are `security/mcp/ScopedTurnTokenCodec` and the MCP transport
  mapper in `configurations/McpServerConfiguration`, both deliberate.

### Testing Requirements
```bash
./gradlew :application:test --tests '*IdempotencyCreatorTest*'
```
`IdempotencyCreatorTest` (Kotest `BehaviorSpec`, no Spring context) pins the window semantics: same
data + same window → same UUID, window boundary (`999` vs `1000` ms) → different, plus regression cases
built from the domain testFixtures (`createMentionInboundCommand`, `createInteractionInboundCommand`,
`createSlashInboundCommand`). Always pass `currentTimeMillis` explicitly — never rely on the wall clock.
`parseRequestBodyData` has no direct spec. `runInTx` has `TransactionTemplateExtTest` on a real H2 transaction
manager; the services build `TransactionTemplate(transactionManager)` themselves, so service specs either stub a
MockK `PlatformTransactionManager` (`getTransaction` → relaxed `TransactionStatus`, `commit`/`rollback` `just Runs`)
when only call order matters, or pass `createH2TransactionManager` when a case claims a rollback or a commit. Build `InboundCommand` inputs with the testFixtures creators, not inline
constructors.

### Common Patterns
- Top-level functions and `object`s only — no Spring annotations, no injected state.
- Optional `currentTimeMillis` parameter with a `System.currentTimeMillis()` default so production
  callers stay terse and tests stay deterministic.
- `inline` + `reified` / `crossinline` for the generic helpers so they read as language features at the
  call site (`transactionTemplate.runInTx<Unit> { ... }`).
- Kotlin named parameters at every call (`IdempotencyCreator.create(data = commandData)`).

## Dependencies

### Internal
- `domain/common/IdempotencyData` — marker interface (`: Serializable`) implemented by `InboundCommand`
- `domain/command/inbound/InboundCommand` — target of `SlashCommandRequestBody.toInboundCommand()`
- `infrastructure/impl/command/slack/SlashCommandRequestBody` — slash wire DTO (`@JsonProperty` snake_case)
- `infrastructure/common/JsonMapper.kt` — the shared `jsonMapper`

### External
Jackson 3 (`tools.jackson.databind`), Spring TX (`TransactionTemplate`), JDK `MessageDigest` / `UUID`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
