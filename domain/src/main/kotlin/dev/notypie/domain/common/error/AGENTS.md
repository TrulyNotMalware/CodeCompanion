<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-09-22 -->

# domain/common/error

## Purpose
The error contract every module builds on: the `ErrorCode` interface, the `ExceptionArgument` detail
record with its `exceptionDetails {}` DSL, and the abstract `CodeCompanionRuntimeException` base. The
validation exceptions thrown by `../Validation.kt` live here too. One file, no imports.

## Key Files
| File | Description |
|------|-------------|
| `Errors.kt` | `interface ErrorCode { message }` (no HTTP status — that mapping is `ControllerAdvice`'s job in `:application`); `internal enum CommonErrorCode` (`VALIDATION_FAILED`); `data class ExceptionArgument(fieldName, value, reason = "")`; `exceptionDetails { "field" value v because "reason" }` via `ExceptionDetailsBuilder` / `ReasonBuilder`; `abstract class CodeCompanionRuntimeException(val errorCode, val details) : RuntimeException(errorCode.message)`; `internal ValidationException` and `internal ValidationExceptionWithName(className, ...)` |

## For AI Agents

### Working In This Directory
- `CodeCompanionRuntimeException` exposes `errorCode` and `details` as properties (since 2026-09-22;
  before that the code was dropped after seeding the message). `ErrorCode` deliberately carries no HTTP
  status: the domain is transport-neutral and `ControllerAdvice` decides the response.
- Implementors of `ErrorCode` and subclasses of the base exception: `command/exceptions/CommandErrorCode`
  + `CommandException` (domain, `internal`), `application/exception/PayloadParseException.kt`
  (`PayloadParseErrorCode`), `infrastructure/exception/meeting/DatabaseException.kt` (`JpaErrorCode`).
  Follow that shape for a new module-level error family; do not extend `CommonErrorCode` — it is
  `internal` and reserved for validation.
- `ExceptionDetailsBuilder.details` is `internal`, so outside the module the DSL is the only way to
  populate it (`application/service/mention/SlackMentionEventHandlerImpl.kt` uses it). The
  `ExceptionArgument` constructor is public and `DatabaseException.kt` builds literals directly; prefer
  the DSL in new code.
- `ValidationException` (blank `className`) and `ValidationExceptionWithName` are `internal`: code in
  other modules can only catch the abstract base. Do not widen them — the aggregates' `init` blocks are
  the only intended throw sites.
- Guard-enforced: no `dev.notypie.domain.command` import, no Jackson/Gson/Slack types.

### Testing Requirements
```bash
./gradlew :domain:test --tests 'dev.notypie.domain.common.ValidationBuilderTest'
```
`ValidationBuilderTest` asserts `shouldThrowExactly<ValidationException>` for the blank-`className`
path; the aggregate specs (`meet.entity.MeetingTest`, `standup.entity.*`) assert
`shouldThrow<ValidationExceptionWithName>`. Assert on `fieldName` / `value`; `reason` text is not
contractual. There is no spec for `exceptionDetails {}` on its own — it is exercised through
`command/entity/CommandTest` and the application/infrastructure exception specs.

### Common Patterns
```kotlin
throw UnSupportedCommandException(
    commandType = commandData.kind.toString(),
    errorCode = CommandErrorCode.UNSUPPORTED_COMMAND_TYPE,
    details = exceptionDetails { "commandType" value commandData.kind.toString() because "..." },
)
```
- Infix DSL (`value` / `because`) returning builders so a single expression yields one `ExceptionArgument`.
- `internal` for everything that only `Validation.kt` or the domain module should reach; public only for the
  contract types other modules implement or catch.

## Dependencies

### Internal
None — this package imports nothing. `../Validation.kt` throws its exceptions; `command/`, `meet/`,
`standup/`, `application`, `infrastructure` consume the contract.

### External
None beyond the Kotlin stdlib.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
