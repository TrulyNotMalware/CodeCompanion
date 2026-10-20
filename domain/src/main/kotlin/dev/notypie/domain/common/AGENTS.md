<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-04-28 | Updated: 2026-10-20 -->

# domain/common

## Purpose
The leaf package of the domain module: the `ValidationBuilder` DSL (`validate {}` / `validateAndReturn {}`),
the text helpers in `SafeText.kt` (`escapeMarkup`, `takeSurrogateSafe`, `takeEntitySafe`, `splitLineBreaks`), the `IdempotencyData` marker
interface, and the error contract (`ErrorCode`, `ExceptionArgument`, `exceptionDetails {}`,
`CodeCompanionRuntimeException`) that `command/`, `meet/`, `standup/` and the
`application` / `infrastructure` modules all build their exceptions on. It imports nothing from the repo
outside itself — only `java.time`, `java.io` and `java.util` (identity sets for `or`).

## Key Files
| File | Description |
|------|-------------|
| `Validation.kt` | `ValidationBuilder` plus two entrypoints: public `validate(className = "", block)` (throws) and `internal validateAndReturn(className = "", block)` (returns `List<ExceptionArgument>`). Both `getErrors()` and the thrown exception's `details` are copies, so a later `addError` on the same builder cannot change them. Full operator inventory under Common Patterns |
| `IdempotencyData.kt` | `IdempotencyData : java.io.Serializable` marker. Implemented by `command/inbound/InboundCommand`; `application/common/IdempotencyCreator` turns it into the idempotency `UUID` |
| `SafeText.kt` | Helpers for echoing user- or upstream-supplied text. `String.escapeMarkup()`: replaces `&`, `<`, `>` with `&amp;`, `&lt;`, `&gt;` (ampersand first) so text interpolated into outbound markdown cannot become a broadcast mention or a disguised link. Canonical implementation: `infrastructure/templates/escapeMrkdwn()` delegates to it so domain-built and template-built markdown escape identically. `String.takeSurrogateSafe(maxLength)`: keeps at most `maxLength` UTF-16 units (`take(n = maxLength)`) and drops a trailing high surrogate when the cut lands inside a surrogate pair, so the cut never introduces a split pair (a text that already ends in a lone high surrogate and fits the limit comes back unchanged); it does not keep grapheme clusters whole (a flag, a skin-tone modifier or a ZWJ sequence can still be cut between code points). Callers that reach users: `command/entity/Command.kt`'s `asEchoedToken`, `infrastructure/templates/SlackBlockLimits` (through `takeEntitySafe`), the stored CVE title and raw content (`infrastructure/repository/cve/CveEventRepositoryImpl`; the raw content reaches users through `NoopAiSummarizer` and the sidecar summarizer's prompt), and in the application the standup summary, the CVE digest summary, `/latest`, `NoopAiSummarizer`'s body cap, the calendar failure DM and the `/ask` answer cap. Model-facing callers: `AgentConverseService.sanitizeContextName` (system prompt) and `McpToolGate`'s tool-result fence. Log, audit and storage-only cuts (the sidecar error text, dispatcher failure reasons, the MCP audit key) still use plain `take`. `String.takeEntitySafe(maxLength)`: returns the text unchanged when it fits; otherwise cuts with `takeSurrogateSafe` and drops a trailing partial escape entity: an `&` fewer than `MAX_ENTITY_LENGTH` (5, the length of `&amp;`) UTF-16 units from the end of the cut with no `;` after it is dropped, so an escaped text never ends in `&am`; an `&` exactly five units back (or further) is kept, matching the strict `>` in `takeEntitySafe` — a cut entity is at most four units (`&amp` without its `;`), so such an `&` is plain text. Callers: `infrastructure/templates/SlackBlockLimits`' private `takeSafely` (delegates to it) and `command/outbound/DirectReply.kt` (`askRefusedNotice`'s 1,500-character quote). `String.splitLineBreaks()`: splits on a private `LINE_BREAKS = Regex(pattern = "\\R")` — CRLF (one break), LF, CR, VT (U+000B), FF (U+000C), NEL (U+0085), U+2028 and U+2029 — keeping empty pieces (an empty string gives one empty element). Use it, not `lines()` (CR/LF only), wherever text is split to prefix each line with `> `: rendered markdown treats U+2028/U+2029 as line breaks, so text after one would leave the quote. Callers: `command/outbound/DirectReply.kt` (`askRefusedNotice`), `infrastructure/impl/command/OutboundRenderer.kt` (`askedBy`, `wrappedForQuote`) and `infrastructure/impl/command/slack/SlackMentionMapper.kt` (quote restore). Stdlib only, named transport-neutrally |
| `error/Errors.kt` | `ErrorCode` (`message` only — transport-neutral); `internal enum CommonErrorCode` (only `VALIDATION_FAILED`); `ExceptionArgument(fieldName, value, reason = "")`; `exceptionDetails {}` with `ExceptionDetailsBuilder` / `ReasonBuilder`; `abstract CodeCompanionRuntimeException(val errorCode, val details)`; `internal ValidationException` and `internal ValidationExceptionWithName(className, ...)` |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `error/` | Cross-module error contract: `ErrorCode`, `ExceptionArgument`, `exceptionDetails {}`, `CodeCompanionRuntimeException`, and the validation exceptions thrown by `Validation.kt` (see `error/AGENTS.md`) |

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
- Chaining semantics: `and { }` runs the nested block unconditionally; `or { }` passes when either side
  passes (a satisfied side clears everything the field's chain added; when both fail only the left-hand
  errors stay); `shouldNotBeNullAnd { }` records "must not be null" and skips the block on `null`;
  `ifNotNull { }` skips silently. Nested blocks receive the non-null `Field<T>` as `it`. `or` is exact even
  for a `Field` stored in a `val` and used after other fields were validated (see below).
- `Field` is a plain class with an `internal` constructor carrying `raisedErrors`, an identity set of the
  errors its own chain produced: matchers add through the private `reject`, and `and` / `shouldNotBeNullAnd` /
  `ifNotNull` claim every error their block added and still standing when it ends. Every block (`and`, `or`'s
  right side, `shouldNotBeNullAnd`, `ifNotNull`) gets a child `Field` with its own empty set, so an `or` inside a
  block sees only that block's errors as its left operand: sharing the parent's set let `p1 and { p2 or { p3 } }`
  with `p3` passing erase `p1`'s error. `or` snapshots the field's errors when it starts, runs the right block,
  and then removes by identity either the right block's errors (left passed, or both failed) or the field's own
  left-hand errors (right passed). It never
  uses list positions, so a stored field OR-ed after other fields failed cannot erase their errors, and an
  equal-but-separate error of another field is never mistaken for its own. Do not turn `Field` into a
  `data class` or build `Field`s by hand. `notBlank { }` collects plain `name to value` pairs because nothing
  can be chained on them.
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
non-contractual — `shouldBeNegative` reports "must be positive". The `or` specs include an earlier failing
field followed by an `or` inside `shouldNotBeNullAnd` / `ifNotNull`, a `Field` stored in a `val` and OR-ed
after another field failed, an equal error from another field, an `and` block on the left operand, and a
field that failed before an `and` / `ifNotNull` / `shouldNotBeNullAnd` block whose inner `or` passes, which
pin that `or` never removes errors outside its own operands.

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
`java.time.LocalDateTime`, `java.io.Serializable`, and `java.util.Collections` / `IdentityHashMap` only.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
