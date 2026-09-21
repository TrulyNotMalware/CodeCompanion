<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-04-28 | Updated: 2026-08-28 -->

# infrastructure/exception

## Purpose
Infrastructure-side error types: the `ErrorBroadcaster` port with its `StdoutErrorBroadcaster` and
`KafkaErrorBroadcaster` implementations, and the persistence-facing `DatabaseException` family under
`meeting/` — the exception, its `JpaErrorCode`, a `schemaNotFound { }` builder DSL, and the
`throwIfSchemaNotFound` extension that repositories use to turn a `null` lookup into a structured
not-found error. `DatabaseException` extends the domain's `CodeCompanionRuntimeException` but lives here
because it is a JPA concern; `:application`'s `ControllerAdvice` imports it from
`dev.notypie.exception.meeting`.

## Key Files
| File | Description |
|------|-------------|
| `ErrorBroadcaster.kt` | `interface ErrorBroadcaster { fun broadcastError(message: String) }` |
| `StdoutErrorBroadcaster.kt` | `logger.error { message }` through a file-level `KotlinLogging.logger { }` |
| `KafkaErrorBroadcaster.kt` | `class KafkaErrorBroadcaster(val kafkaTemplate: KafkaTemplate<String, Any>)`; `broadcastError` is `TODO("Not yet implemented")` and throws `NotImplementedError` when called |
| `meeting/DatabaseException.kt` | `DatabaseException(val tableName: String, errorCode: ErrorCode, details: List<ExceptionArgument>)`; `enum JpaErrorCode { TABLE_NOT_FOUND(404, "Table not found.") }`; `NotFoundExceptionBuilder` (`table(KClass)` / `table(String)`, `field(name) withValue value`, `reason(message)`, `internal build()`); `schemaNotFound(init): Nothing`; `inline fun <reified T : Any> T?.throwIfSchemaNotFound(fieldName: String, fieldValue: Any, reason: String? = null): T` |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `meeting/` | `DatabaseException`, `JpaErrorCode`, the `schemaNotFound` DSL, and the `throwIfSchemaNotFound` extension (despite the name, used by the standup lane too) (see `meeting/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- **`ErrorBroadcaster` is wired but dormant.** `:application`'s `ConsumerConfig.kt` registers
  `KafkaErrorBroadcaster` when `slack.app.mode.event-publisher` is `KAFKA`
  (`KafkaEventPublisherConfig`) and `StdoutErrorBroadcaster` under `@ConditionalOnMissingBean` when it is
  `APPLICATION_EVENT` (`ApplicationEventPublisherConfig`, the default). Nothing injects the port or calls
  `broadcastError` anywhere in the codebase. If you become the first caller, implement
  `KafkaErrorBroadcaster` first — in Kafka mode the bean that would answer is the `TODO` stub, and
  `NotImplementedError` is an `Error`, so a `catch (e: Exception)` will not contain it.
- **`throwIfSchemaNotFound` names the receiver's static type, not the table.** `tableName` is
  `T::class.simpleName`, and both current callers invoke it after mapping: `MeetingRepositoryImpl.getMeeting`
  (`?.toMeetingDto().throwIfSchemaNotFound(fieldName = "id", ...)`) reports `MeetingDto`, and
  `StandupRepositoryImpl.getRoutine` (`fieldName = "routineUid"`) reports `RoutineDto`. Call it on the
  schema object if you want the entity name in the error.
- **The HTTP status in `JpaErrorCode` never reaches a response.** `CodeCompanionRuntimeException` keeps
  only `details` and the message (`"Table not found."`); `errorCode` is not retained as a property. The
  application `ControllerAdvice.handleDatabaseException` body is empty, so a `DatabaseException` escaping a
  controller yields an empty 200, not a 404.
- **`schemaNotFound { }` has no callers.** The DSL and `throwIfSchemaNotFound` build the same exception
  (`TABLE_NOT_FOUND`, one `ExceptionArgument`); prefer the extension for the null-to-exception case and
  reserve the DSL for a custom `reason` on a known table. Add new not-found helpers as top-level extensions
  in `meeting/`, not as methods on entity classes.
- Domain-level errors (`ValidationException`, `CommonErrorCode`) stay in `domain/common/error`; this package
  is for infrastructure and persistence errors only.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.exception.*' --tests 'dev.notypie.repository.meeting.*'
```
`exception/DatabaseExceptionTest` exists but is an empty `BehaviorSpec` (a single `given` with no
`when`/`then`) and passes vacuously — it is a placeholder, not coverage. The real assertions live in
`repository/meeting/MeetingRepositoryImplTest`, which does `shouldThrow<DatabaseException>` for a missing
meeting. When touching `throwIfSchemaNotFound`, fill the placeholder: assert the non-null path returns the
receiver, and the null path throws a `DatabaseException` whose `details` carry the `fieldName` /
`fieldValue.toString()` and whose `tableName` is the receiver type's simple name. There is no spec for
either broadcaster.

### Common Patterns
- Kotlin call sites use named parameters (`throwIfSchemaNotFound(fieldName = ..., fieldValue = ...)`).
- `kotlin-logging` lambda form for all log calls: `logger.error { message }`.
- Not-found helpers are `inline reified` extensions on `T?` returning `T`, so callers stay non-null after
  the call.

## Dependencies

### Internal
- `domain/common/error/Errors.kt` — `CodeCompanionRuntimeException`, `ErrorCode`, `ExceptionArgument`

### External
Spring Kafka `KafkaTemplate` (constructor dependency of `KafkaErrorBroadcaster`), `kotlin-logging`
(`io.github.oshai.kotlinlogging`).

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
