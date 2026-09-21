<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# infrastructure/exception/meeting

## Purpose
The persistence-side not-found error family: `DatabaseException`, its `JpaErrorCode`, the
`schemaNotFound { }` builder DSL, and the `throwIfSchemaNotFound` extension that turns a `null` lookup into
a structured exception. Despite the package name it is used by the meeting *and* standup lanes.

## Key Files
| File | Description |
|------|-------------|
| `DatabaseException.kt` | `class DatabaseException(val tableName: String, errorCode: ErrorCode, details: List<ExceptionArgument>) : CodeCompanionRuntimeException`; `enum JpaErrorCode { TABLE_NOT_FOUND(404, "Table not found.") }`; `NotFoundExceptionBuilder` with `table(KClass<*>)` / `table(String)`, `field(name) withValue value`, `reason(message)` and an `internal build()`; `fun schemaNotFound(init: NotFoundExceptionBuilder.() -> Unit): Nothing`; `inline fun <reified T : Any> T?.throwIfSchemaNotFound(fieldName: String, fieldValue: Any, reason: String? = null): T` |

## For AI Agents

### Working In This Directory
- **Two live call sites, both after DTO mapping:** `MeetingRepositoryImpl.getMeeting` (`fieldName = "id"`)
  and `StandupRepositoryImpl.getRoutine` (`fieldName = "routineUid"`). Because `tableName` is
  `T::class.simpleName`, the reported table is `MeetingDto` / `RoutineDto`, not the entity. Call the
  extension on the schema object if the entity name matters.
- **The default `reason`** is `"<T> with <fieldName>=<fieldValue> not found."`; pass `reason` only to
  override it. `fieldValue` is stored via `toString()`, so UUIDs and Longs are fine.
- **`schemaNotFound { }` has no callers** and `build()` is `internal`, so the DSL cannot be used from
  `:application`. Prefer the extension; add new helpers as top-level extensions in this file.
- `errorCode` is not retained as a property on `CodeCompanionRuntimeException`, so the `404` in
  `JpaErrorCode` never reaches an HTTP response (see `../AGENTS.md`).

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.meeting.MeetingRepositoryImplTest' \
  --tests 'dev.notypie.exception.DatabaseExceptionTest'
```
`DatabaseExceptionTest` is an empty placeholder `BehaviorSpec`; the real assertion is the
`shouldThrow<DatabaseException>` in `MeetingRepositoryImplTest`. When you touch this file, fill the
placeholder with the null and non-null paths of `throwIfSchemaNotFound`.

### Common Patterns
- `inline reified` extension on `T?` returning `T` so call sites stay non-null.
- Named arguments at every Kotlin call site (`throwIfSchemaNotFound(fieldName = ..., fieldValue = ...)`).

## Dependencies

### Internal
- `domain/common/error/Errors.kt` — `CodeCompanionRuntimeException`, `ErrorCode`, `ExceptionArgument`
- Consumers: `repository/meeting/MeetingRepositoryImpl`, `repository/standup/StandupRepositoryImpl`,
  `:application` `ControllerAdvice`

### External
None beyond `kotlin.reflect.KClass`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
