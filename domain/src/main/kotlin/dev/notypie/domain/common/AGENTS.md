<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-04-28 | Updated: 2026-09-22 -->

# domain/common

## Purpose
The leaf package of the domain module: the `ValidationBuilder` DSL (`validate {}` / `validateAndReturn {}`),
the `IdempotencyData` marker interface, and the error contract (`ErrorCode`, `ExceptionArgument`,
`exceptionDetails {}`, `CodeCompanionRuntimeException`) that `command/`, `meet/`, `standup/` and the
`application` / `infrastructure` modules all build their exceptions on. It imports nothing from the repo
outside itself — only `java.time` and `java.io`.

## Key Files
| File | Description |
|------|-------------|
| `Validation.kt` | (renamed from `Utils.kt` on 2026-09-22; `or` now clears the field's errors when either side passes) `ValidationBuilder` plus two entrypoints: public `validate(className = "", block)` (throws) and `internal validateAndReturn(className = "", block)` (returns `List<ExceptionArgument>`). Full operator inventory under Common Patterns |
| `IdempotencyData.kt` | `IdempotencyData : java.io.Serializable` marker. Implemented by `command/inbound/InboundCommand`; `application/common/IdempotencyCreator` turns it into the idempotency `UUID` |
| `error/Errors.kt` | `ErrorCode` (`message` only — transport-neutral); `internal enum CommonErrorCode` (only `VALIDATION_FAILED`); `ExceptionArgument(fieldName, value, reason = "")`; `exceptionDetails {}` with `ExceptionDetailsBuilder` / `ReasonBuilder`; `abstract CodeCompanionRuntimeException(val errorCode, val details)`; `internal ValidationException` and `internal ValidationExceptionWithName(className, ...)`; `internal sealed ErrorResponse` (unreferenced) |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `error/` | Cross-module error contract: `ErrorCode`, `ExceptionArgument`, `exceptionDetails {}`, `CodeCompanionRuntimeException`, and the validation exceptions thrown by `Utils.kt` (see `error/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- `validate(className = this.javaClass.simpleName) { ... }` inside `init` is how every aggregate
  (`meet/entity/Meeting.kt`, `standup/entity/Routine.kt`, `standup/entity/StandupSession.kt`, ...) enforces
  invariants. Failures accumulate — nothing short-circuits — and are thrown once at the end:
  `ValidationExceptionWithName` when `className` is non-blank, plain `ValidationException` otherwise.
  Both are `internal`, carry `CommonErrorCode.VALIDATION_FAILED`, and expose the list as
  `CodeCompanionRuntimeException.details`; code outside the module catches the abstract base
  (see `command/entity/context/form/RequestMeetingContext.kt`, which renders `details` into an ephemeral).
- `validateAndReturn {}` is `internal` and has no production caller — only `ValidationBuilderTest` uses it
  to inspect the error list without throwing. Use `validate {}` in domain code.
- Chaining semantics: `and { }` runs the nested block unconditionally; `or { }` runs it and then removes
  every error the block added; `shouldNotBeNullAnd { }` records "must not be null" and skips the block
  on `null`; `ifNotNull { }` skips silently. Nested blocks receive the non-null `Field<T>` as `it`.
- Bounds are not symmetric across types: `shouldBeShorterThan(max)` / `shouldBeLongerThan(min)` only fail
  on `length > max` / `length < min` (equality passes), whereas `shouldBeLessThan(max)` fails on
  `value >= max`. `Meeting.MAX_TITLE_LENGTH = 20` therefore admits a 20-character title.
- `ErrorCode` is the only public error enum contract. `CommonErrorCode` is `internal`; each owner defines
  its own enum: `command/exceptions/CommandErrorCode` (`internal`, domain),
  `application/exception/PayloadParseErrorCode`, `infrastructure/exception/meeting/JpaErrorCode`.
- Build `details` with `exceptionDetails { "field" value actual because "reason" }` rather than
  `ExceptionArgument(...)` literals. `ExceptionDetailsBuilder.details` is `internal`, so the DSL is the
  only way to populate it from another module.
- `IdempotencyCreator` derives the key by Jackson-serializing the `IdempotencyData` to JSON and hashing it
  (SHA-256), not by Java serialization — the `Serializable` bound is incidental. Keep implementors as
  plain `data class`es with deterministic, Jackson-friendly fields.
- Framework-free and `command`-free: `DomainLayeringGuardTest` fails the build if this package imports
  `dev.notypie.domain.command`, Jackson/Gson, or any Slack SDK type. Kotlin stdlib and JDK only.

### Testing Requirements
```bash
./gradlew :domain:test --tests '*ValidationBuilder*'
```
Spec: `domain/src/test/kotlin/dev/notypie/domain/common/ValidationBuilderTest.kt` — Kotest `BehaviorSpec`,
one `given` per operator family, driven through `validateAndReturn {}` so it can count and inspect
errors. Entity specs (`MeetingTest`, `RoutineTest`, `StandupSessionTest`) cover the throw path with
`shouldThrow<ValidationExceptionWithName>`. Assert on `fieldName` and `value`; treat `reason` text as
non-contractual — `notBlank` interpolates the whole `Field` into its reason and `shouldBeNegative`
reports "must be positive".

### Common Patterns
```kotlin
// Aggregate init block (lines taken from Meeting and Routine)
validate(className = this.javaClass.simpleName) {
    notBlank {
        "publisher" of publisher
        "title" of title
    }
    "meeting participants" of members.size shouldBeLessThanOrEqualTo MAX_PARTICIPANTS
    "meeting title length" of title shouldBeShorterThan MAX_TITLE_LENGTH
    "meeting start time" of startAt shouldBeAfter LocalDateTime.now()
    "questions" of questions shouldHaveMinSize MIN_QUESTIONS
    ("weekdays" of weekdays).shouldNotBeEmpty(message = "weekdays must include at least one day")
    ("cutoffOffset" of cutoffOffset).shouldSatisfy("must be positive") { it > Duration.ZERO }
}

// Exception details (command/entity/Command.kt)
throw UnSupportedCommandException(
    commandType = commandData.kind.toString(),
    errorCode = CommandErrorCode.UNSUPPORTED_COMMAND_TYPE,
    details =
        exceptionDetails {
            "commandType" value commandData.kind.toString() because
                "handleInteraction() is required only for reaction command type"
        },
)
```
`ValidationBuilder` operator inventory (every operator returns the `Field` so calls chain):
- Chaining: `and { }`, `or { }`, `shouldNotBeNullAnd { }`, `ifNotNull { }`
- String: `notBlank { "a" of x; ... }`, `shouldBeShorterThan`, `shouldBeLongerThan`,
  `shouldMatchPattern` (`Regex` or `String`), `shouldBeEmail()`, `shouldNotBeNullOrBlank()`
- Int: `shouldBeLessThan`, `shouldBeLessThanOrEqualTo`, `shouldBeGreaterThan`,
  `shouldBeGreaterThanOrEqualTo`, `shouldBeBetween(IntRange)`, `shouldBePositive()`,
  `shouldBeNegative()`, `shouldBeNonNegative()`
- `LocalDateTime`: `shouldBeAfter`, `shouldBeBefore`, `shouldBeInFuture()`, `shouldBeInPast()`
- Collection: `shouldHaveSize`, `shouldHaveMinSize`, `shouldHaveMaxSize`, `shouldNotBeEmpty(message)`
- Any `T`: `shouldBeOneOf(Collection<T>)`, `shouldSatisfy { }`, `shouldSatisfy(message) { }`
- Escape hatches: `addError(fieldName, value, reason)`, `hasErrors()`, `getErrors()`

## Dependencies

### Internal
None. `common` is a leaf: `command/`, `meet/`, `standup/` import it (one-way, guard-enforced), and
`application` / `infrastructure` extend `CodeCompanionRuntimeException` and implement `ErrorCode`.

### External
`java.time.LocalDateTime` and `java.io.Serializable` only.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
