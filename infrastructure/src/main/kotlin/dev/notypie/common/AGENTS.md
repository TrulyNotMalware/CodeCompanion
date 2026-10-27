<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-04-28 | Updated: 2026-10-27 -->

# infrastructure/common

## Purpose
Context-free utilities shared by the other infrastructure packages and by `:application`: the module-wide
`jsonMapper` singleton (Jackson 3 `tools.jackson` with Kotlin support and deterministic key ordering) and the
two failure-boundary helpers (`containFailure`, `runCatchingExceptions`) that `:application` and `:infrastructure` boundaries use instead of `runCatching` (`:domain`
cannot import them; `Command.handleEvent` has its own `catch (Exception)`). Nothing here is a Spring bean.

## Key Files
| File | Description |
|------|-------------|
| `JsonMapper.kt` | Top-level `val jsonMapper: JsonMapper` built with `findAndAddModules()`, a `KotlinModule` enabling `UseJavaDurationConversion` and `KotlinPropertyNameAsImplicitName`, `SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS`, and `DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS` disabled (ISO-8601 dates) |
| `ExceptionBoundary.kt` | Two public `inline` helpers that catch `Exception`, never `Error` (2026-10-15). `containFailure(onFailure: (Exception) -> Unit, block: () -> Unit)` runs `block` and hands an `Exception` to `onFailure` (moved here from `application/service/standup/StandupSchedulingService.kt`); `runCatchingExceptions(block: () -> T): Result<T>` returns `Result.success` or `Result.failure(exception)`. Both restore the interrupt flag and rethrow an `InterruptedException`, and neither catches an `Error`, which propagates unchanged. `containFailure` users: scheduler ticks and per-row loops (`DailyAgendaScheduler`, `MeetingReminderScheduler`, `MeetingReminderSchedulingService`, `OutboxRecoveryScheduler`, `OutboxRetentionScheduler`, `StandupScheduler`, `StandupSchedulingService`, `CalendarSyncScheduler`, `CalendarSyncService`, `GoogleOAuthStateRetentionScheduler`, `GoogleTokenRevocationWorker`, `AgentAskThreadStarter`, `AgentRetentionScheduler`, the per-item loops of `CveCollector` / `CveSummaryWorker`), `McpToolGate`'s audit write, `SocketModeReceiver` and the start of an agent turn before its sidecar call (`AgentConverseService`, 2026-10-26). `runCatchingExceptions` users: the `Result`-shaped edges (`McpToolGate`'s tool body, the relay's `renewClaim` / `deferClaim`, `StandupRoutineSetupService.buildRoutine`, `ApplicationMessageDispatcher.dispatchImmediate`, `RestClientRequester.performRequest`, `GithubReleaseSourceAdapter.fetch`; `toOutboxMessage` uses a typed `catch (Exception)` instead) |

## For AI Agents

### Working In This Directory
- **`jsonMapper` is the shared instance; import `dev.notypie.common.jsonMapper`.** Current consumers:
  `impl/agent/SidecarAgentClient`, `impl/cve/NvdCveSourceAdapter`, `impl/cve/GithubReleaseSourceAdapter`,
  `repository/outbox/schema/OutboxMessage` (`MutableMap<String, Any>.toOutboxMessage()` via
  `convertValue`), `templates/ModalTemplateBuilder`, and in `:application` `IdempotencyCreator`,
  `SlackRequestParser`, `SlackInteractionHandlerImpl`, `SlackMentionEventHandlerImpl`,
  `SocketModeReceiver`, `mcp/DomainReadTools` (the `cve_latest` audit summary). `ORDER_MAP_ENTRIES_BY_KEYS` is what makes idempotency keys and outbox payloads
  byte-stable, so do not disable it.
- **The one sanctioned second mapper is `repository/outbox/OutboundMessageCodec`.** It copies this base
  configuration and adds polymorphic mix-ins that must not leak into general serialization. If you change
  a base feature here, mirror it in the codec or the two will drift.
- **Production code never catches `Throwable` or `Error` at a boundary.** Use `containFailure` for a side-effect
  block, `runCatchingExceptions` where the caller wants a `Result`, or a typed `try` / `catch`. Naming `Throwable` or
  `Error` in a `catch` is allowed only where the same instance is rethrown after clean-up or a log line
  (`SlackRequestVerificationFilter`, `SlackInteractionHandlerImpl`'s manual transaction, `SlackUserProfileResolver`'s
  in-flight future, `SocketModeReceiver.logErrorThenRethrow`), plus one translation (decision #48's exception
  clause): `DebeziumLogTailingProcessor.consume` rethrows an `Error` as `ListenerErrorException`, because
  spring-kafka stops the listener container on an `Error` instead of calling its error handler. Inside a
  `TransactionTemplate` callback neither helper fits, because a rethrown interrupt comes back wrapped in
  `UndeclaredThrowableException`; `application/common/runInTx` restores the flag and returns a failure instead.
  Pure parse sites (enum lookups, JSON peeks, date parses; the relay's and the CDC listener's `UUID.fromString` catch `IllegalArgumentException` only, 2026-10-19) and the two watchdog
  `runCatching { stream.close() }` calls (`impl/http/BoundedHttp`, `impl/agent/SidecarAgentClient`) are left as they
  are.
- **Blocking code only.** Both helpers catch `Exception`, which in a coroutine would also swallow
  `CancellationException`; the codebase has no coroutines today. Do not call a suspending function inside them.
- Keep this package free of Spring beans and of imports from other infrastructure packages so the utilities
  stay testable without a context.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.common.*'
```
Plain kotest `BehaviorSpec`, no Spring context: `ExceptionBoundaryTest` (both helpers: an `Exception` is contained, an `Error`
escapes as the same instance, an interrupt is rethrown with the flag set). A change to `jsonMapper` configuration is also covered indirectly by
`repository/outbox/OutboundMessageCodecTest` and the `impl/cve/*SourceAdapterTest` specs.

### Common Patterns
- Import `jsonMapper` by name; never re-declare, alias, or `rebuild()` it at a call site.
- Kotlin call sites use named parameters (`readValue(content = ...)`).
- No `this.` in utility functions.

## Dependencies

### Internal
- None outside this package.

### External
Jackson 3 (`tools.jackson.databind`, `tools.jackson.module.kotlin` — declared in
`infrastructure/build.gradle.kts` via `jackson-bom` and `jackson-module-kotlin`).

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
