<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-04-28 | Updated: 2026-09-30 -->

# infrastructure/exception

## Purpose
Infrastructure-side error types: the `ErrorBroadcaster` port with its `StdoutErrorBroadcaster`
implementation, and the persistence-facing `DatabaseException` family under
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
| `meeting/DatabaseException.kt` | `DatabaseException(val tableName: String, errorCode: ErrorCode, details: List<ExceptionArgument>)`; `enum JpaErrorCode(message) { TABLE_NOT_FOUND("Table not found.") }`; `NotFoundExceptionBuilder` (`table(KClass)` / `table(String)`, `field(name) withValue value`, `reason(message)`, `internal build()`); `schemaNotFound(init): Nothing`; `inline fun <reified T : Any> T?.throwIfSchemaNotFound(fieldName: String, fieldValue: Any, reason: String? = null): T` |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `meeting/` | `DatabaseException`, `JpaErrorCode`, the `schemaNotFound` DSL, and the `throwIfSchemaNotFound` extension (despite the name, used by the standup lane too) (see `meeting/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- **`ErrorBroadcaster` is wired but dormant.** `:application`'s `ConsumerConfig.kt` registers
  `StdoutErrorBroadcaster` in every mode (`ErrorBroadcasterConfig`, `@ConditionalOnMissingBean`). Nothing
  injects the port or calls `broadcastError` anywhere in the codebase. The former `KafkaErrorBroadcaster`
  was a `TODO()` stub that would have thrown `NotImplementedError` from an error path and was removed on
  2026-09-22; a Kafka-backed implementation needs a real error topic and must never throw.
- **`throwIfSchemaNotFound` names the receiver's static type, not the table.** `tableName` is
  `T::class.simpleName`, and both current callers invoke it after mapping: `MeetingRepositoryImpl.getMeeting`
  (`?.toMeetingDto().throwIfSchemaNotFound(fieldName = "id", ...)`) reports `MeetingDto`, and
  `StandupRepositoryImpl.getRoutine` (`fieldName = "routineUid"`) reports `RoutineDto`. Call it on the
  schema object if you want the entity name in the error.
- **`JpaErrorCode` carries no HTTP status.** `ErrorCode` is transport-neutral (message only), and
  `CodeCompanionRuntimeException` keeps `errorCode` and `details` as properties. The application
  `ControllerAdvice.handleDatabaseException` logs the table and message and answers
  `500 {"error":"internal_error"}`, so a `DatabaseException` escaping a controller is a 500, not a 404.
- **`schemaNotFound { }` has no production callers** (only `DatabaseExceptionTest`). The DSL and `throwIfSchemaNotFound` build the same exception
  (`TABLE_NOT_FOUND`, one `ExceptionArgument`); prefer the extension for the null-to-exception case and
  reserve the DSL for a custom `reason` on a known table. Add new not-found helpers as top-level extensions
  in `meeting/`, not as methods on entity classes.
- Domain-level errors (`ValidationException`, `CommonErrorCode`) stay in `domain/common/error`; this package
  is for infrastructure and persistence errors only.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.exception.*' --tests 'dev.notypie.repository.meeting.*'
```
`exception/DatabaseExceptionTest` asserts the helper contract: the non-null path returns the receiver, the
null path throws a `DatabaseException` with `tableName` = the receiver type's simple name,
`errorCode = TABLE_NOT_FOUND`, and one `ExceptionArgument` carrying `fieldName`, `fieldValue.toString()` and the
default reason; the `schemaNotFound { }` builder is covered too. `repository/meeting/MeetingRepositoryImplTest`
still asserts the repository-side throw for a missing meeting. There is no spec for either broadcaster.

### Common Patterns
- Kotlin call sites use named parameters (`throwIfSchemaNotFound(fieldName = ..., fieldValue = ...)`).
- `kotlin-logging` lambda form for all log calls: `logger.error { message }`.
- Not-found helpers are `inline reified` extensions on `T?` returning `T`, so callers stay non-null after
  the call.

## Dependencies

### Internal
- `domain/common/error/Errors.kt` — `CodeCompanionRuntimeException`, `ErrorCode`, `ExceptionArgument`

### External
`kotlin-logging` (`io.github.oshai.kotlinlogging`).

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
